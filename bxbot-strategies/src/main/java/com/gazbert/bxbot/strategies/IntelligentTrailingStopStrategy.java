/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Gareth Jon Lynch
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.gazbert.bxbot.strategies;

import com.gazbert.bxbot.strategies.helper.*;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.strategy.api.TradingStrategy;
import com.gazbert.bxbot.trading.api.*;
import com.gazbert.bxbot.trading.api.util.JsonBarsSerializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

import org.ta4j.core.*;
import org.ta4j.core.num.Num;

@Component("intelligentTrailingStopStrategy") // used to load the strategy using Spring bean injection
public class IntelligentTrailingStopStrategy implements TradingStrategy {

    private static final Logger LOG = LogManager.getLogger();

    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#.########");
    private static final BigDecimal oneHundred = new BigDecimal("100");
    private IntelligentSellPriceCalculator sellPriceCalculator;

    /**
     * The market this strategy is trading on.
     */

    private Market market;


    /* market data downloaded and stored during the engine lifetime */
    private BarSeries series;

    private IntelligentLimitAdapter intelligentLimitAdapter;
    private IntelligentPriceTracker priceTracker;
    private IntelligentStateTracker stateTracker;
    private IntelligentStateTracker.OrderPriceCalculator buyPriceCalculator;


    /**
     * Initialises the Trading Strategy. Called once by the Trading Engine when the bot starts up;
     * it's a bit like a servlet init() method.
     *
     * @param tradingApi the Trading API. Use this to make trades and stuff.
     * @param market     the market for this strategy. This is the market the strategy is currently
     *                   running on - you wire this up in the markets.yaml and strategies.yaml files.
     * @param config     configuration for the strategy. Contains any (optional) config you set up in the
     *                   strategies.yaml file.
     */
    @Override
    public void init(TradingApi tradingApi, Market market, StrategyConfig config) {
        LOG.info(() -> "Initialising Trading Strategy...");
        this.market = market;
        this.intelligentLimitAdapter = new IntelligentLimitAdapter(config);
        BarSeries loadedSeries = JsonBarsSerializer.loadSeries("not_available.json");
        if (loadedSeries == null) {
            LOG.info(() -> "No series to be loaded found. Create a new series.");
            loadedSeries = new BaseBarSeriesBuilder().withName(market.getName() + "_" + System.currentTimeMillis()).build();
        }
        series = loadedSeries;
        priceTracker = new IntelligentPriceTracker(tradingApi, market, series);
        stateTracker = new IntelligentStateTracker(tradingApi, market, priceTracker, config);
        buyPriceCalculator = new IntelligentBuyPriceCalculator(market, priceTracker, config);
        buyPriceCalculator = new StaticBuyPriceCalculator(new BigDecimal("25")); // TODO remove

        sellPriceCalculator = createIntelligentSellPriceCalcualtor(tradingApi);

        LOG.info(() -> "Trading Strategy initialised successfully!");
    }

    private IntelligentSellPriceCalculator createIntelligentSellPriceCalcualtor(TradingApi tradingApi) {
        return new IntelligentSellPriceCalculator(priceTracker, new IntelligentSellPriceCalculator.IntelligentSellPriceParameters() {
            @Override
            public BigDecimal getBuyFee() throws TradingApiException, ExchangeNetworkException {
                return tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(market.getId());
            }

            @Override
            public BigDecimal getSellFee() throws TradingApiException, ExchangeNetworkException {
                return tradingApi.getPercentageOfSellOrderTakenForExchangeFee(market.getId());
            }

            @Override
            public BigDecimal getCurrentBuyOrderPrice() {
                return stateTracker.getCurrentBuyOrderPrice();
            }

            @Override
            public BigDecimal getCurrentSellOrderPrice() {
                return stateTracker.getCurrentSellOrderPrice();
            }

            @Override
            public BigDecimal getCurrentSellStopLimitPercentageBelowBreakEven() {
                return intelligentLimitAdapter.getCurrentSellStopLimitPercentageBelowBreakEven();
            }

            @Override
            public BigDecimal getCurrentSellStopLimitPercentageAboveBreakEven() {
                return intelligentLimitAdapter.getCurrentSellStopLimitPercentageAboveBreakEven();
            }

            @Override
            public BigDecimal getCurrentSellStopLimitPercentageMinimumAboveBreakEven() {
                return intelligentLimitAdapter.getCurrentSellStopLimitPercentageMinimumAboveBreakEven();
            }
        });
    }

    /**
     * This is the main execution method of the Trading Strategy. It is where your algorithm lives.
     *
     * <p>It is called by the Trading Engine during each trade cycle, e.g. every 60s. The trade cycle
     * is configured in the {project-root}/config/engine.yaml file.
     *
     * @throws StrategyException if something unexpected occurs. This tells the Trading Engine to
     *                           shutdown the bot immediately to help prevent unexpected losses.
     */
    @Override
    public void execute() throws StrategyException {

        try {

            priceTracker.updateMarketPrices();

            intelligentLimitAdapter.printCurrentStatistics();
            IntelligentStrategyState strategyState = stateTracker.getCurrentState();

            switch (strategyState) {
                case NEED_BUY:
                    executeBuyPhase();
                    break;
                case NEED_SELL:
                    executeSellPhase();
                    break;
                case WAIT_FOR_BUY:
                    executeCheckOfTheBuyOrder();
                    break;
                case WAIT_FOR_SELL:
                    executeCheckOfTheSellOrder();
                    break;
                default:
                    throw new StrategyException("Unknown strategy state encounted: " + strategyState);
            }
        } catch (TradingApiException | ExchangeNetworkException | StrategyException e) {
            // We are just going to re-throw as StrategyException for engine to deal with - it will
            // shutdown the bot.
            // TODO reanable JsonBarsSerializer.persistSeries(series, market.getId() + System.currentTimeMillis() + ".json");

            LOG.error(
                    market.getName()
                            + " Failed to perform the strategy because Exchange threw TradingApiException, ExchangeNetworkexception or StrategyException. "
                            + "Telling Trading Engine to shutdown bot!",
                    e);
            throw new StrategyException(e);
        }
    }

    private void executeCheckOfTheSellOrder() throws TradingApiException, ExchangeNetworkException, StrategyException {
        LOG.info(() -> market.getName() + " State: Wait for SELL order to fulfill.");
        sellPriceCalculator.logCurrentSellStatistics();
        stateTracker.trackRunningSellOrder(sellPriceCalculator, newState -> {
            switch (newState) {
                case NEED_SELL:
                    executeSellPhase();
                    break;
                case NEED_BUY:
                    executeBuyPhase();
                    break;
                default:
                    throw new StrategyException("Invalid state encountered: " + newState + ". No idea how to proceed...");
            }
        }, intelligentLimitAdapter);
    }

    private void executeCheckOfTheBuyOrder() throws TradingApiException, ExchangeNetworkException, StrategyException {
        LOG.info(() -> market.getName() + " State: Wait for BUY order to fulfill.");
        stateTracker.trackRunningBuyOrder(newState -> {
            switch (newState) {
                case NEED_SELL:
                    executeSellPhase();
                    break;
                case NEED_BUY:
                    executeBuyPhase();
                    break;
                default:
                    throw new StrategyException("Invalid state encountered: " + newState + ". No idea how to proceed...");
            }
        });
    }

    private void executeBuyPhase() throws TradingApiException, ExchangeNetworkException, StrategyException {
        LOG.info(() -> market.getName() + " BUY phase - check if the market moved up.");

        if (marketMovedUp()) {
            LOG.info(() -> market.getName() + " BUY phase - The market moved up. Place a BUY order on the exchange -->");
            stateTracker.placeBuyOrder(buyPriceCalculator);
        } else {
            LOG.info(() -> market.getName() + " BUY phase - The market gain needed to place a BUY order was not reached. Wait for the next trading strategy tick.");
        }
    }

    private boolean marketMovedUp() {
        BigDecimal currentPercentageGainNeededForBuy = intelligentLimitAdapter.getCurrentPercentageGainNeededForBuy();
        int currentLowestPriceLookbackCount = intelligentLimitAdapter.getCurrentLowestPriceLookbackCount();
        BigDecimal lowestAskPrice = calulateLowestAskPriceIn(currentLowestPriceLookbackCount);
        int currentTimesAboveLowestPriceNeeded = intelligentLimitAdapter.getCurrentTimesAboveLowestPriceNeeded();
        BigDecimal cleanedMarketPrice = calulateLowestAskPriceIn(currentTimesAboveLowestPriceNeeded);
        BigDecimal amountToMoveUp = lowestAskPrice.multiply(currentPercentageGainNeededForBuy);
        BigDecimal goalToReach = lowestAskPrice.add(amountToMoveUp);
        BigDecimal percentageChangeMarketToMinimum = getPercentageChange(priceTracker.getAsk(), lowestAskPrice);
        BigDecimal percentageChangeCleanedMarketToMinimum = getPercentageChange(cleanedMarketPrice, lowestAskPrice);
        LOG.info(() -> market.getName() + "\n" +
                "######### BUY ORDER STATISTICS #########\n" +
                " * Price needed: " + priceTracker.formatWithCounterCurrency(goalToReach) +
                "\n * Current ask price: " + priceTracker.getFormattedAsk() +
                "\n * Current cleaned ask price (in " + currentTimesAboveLowestPriceNeeded + " ticks): " + priceTracker.formatWithCounterCurrency(cleanedMarketPrice) +
                "\n * Minimum seen ask price (in " + currentLowestPriceLookbackCount + " ticks): " + priceTracker.formatWithCounterCurrency(lowestAskPrice) +
                "\n * Gain needed from this minimum price: " + DECIMAL_FORMAT.format(currentPercentageGainNeededForBuy.multiply(oneHundred)) +
                "% = " + priceTracker.formatWithCounterCurrency(amountToMoveUp) +
                "\n * Current market above minimum: " + DECIMAL_FORMAT.format(percentageChangeMarketToMinimum) + "%" +
                "\n * Cleaned market above minimum (" + currentTimesAboveLowestPriceNeeded + " ticks): " + DECIMAL_FORMAT.format(percentageChangeCleanedMarketToMinimum) + "%\n" +
                "########################################");

        return cleanedMarketPrice.compareTo(goalToReach) > 0;
    }

    private BigDecimal getPercentageChange(BigDecimal newPrice, BigDecimal priceToCompareAgainst) {
        return newPrice.subtract(priceToCompareAgainst).divide(priceToCompareAgainst, 10, RoundingMode.HALF_UP).multiply(oneHundred);
    }

    private BigDecimal calulateLowestAskPriceIn(int ticks) {
        int currentEndIndex = series.getEndIndex();
        Num result = series.getBar(currentEndIndex).getHighPrice();
        int currentBeginIndex = series.getBeginIndex();

        int spanStartIndex = currentEndIndex - ticks;
        int availableStartIndex = Math.max(currentBeginIndex, spanStartIndex);
        for (int i = availableStartIndex; i < currentEndIndex; i++) {
            result = series.getBar(i).getHighPrice().min(result);
        }
        return (BigDecimal) result.getDelegate();
    }

    private void executeSellPhase() throws TradingApiException, ExchangeNetworkException, StrategyException {
        LOG.info(() -> market.getName() + " SELL phase - create a SELL order for the last sucessfull BUY.");
        stateTracker.placeSellOrder(sellPriceCalculator);
    }
}
