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

    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat( "#.########");
    private static final BigDecimal oneHundred = new BigDecimal("100");
    private static final BigDecimal twentyFive = new BigDecimal("25");

    /**
     * Reference to the main Trading API.
     */
    private TradingApi tradingApi;

    /**
     * The market this strategy is trading on.
     */

    private Market market;
    /**
     * The % of the the available counter currency balance to be used for buy orders. This was loaded from the strategy
     * entry in the {project-root}/config/strategies.yaml config file.
     */
    private BigDecimal configuredPercentageOfCounterCurrencyBalanceToUse;
    /**
     * The emergency stop that should always be left over in the counter currency balance.
     * This is an entry in the {project-root}/config/strategies.yaml config file.
     * It should be the same as the value of 'emergencyStopBalance' in the {project-root}/config/engine.yaml
     */
    private BigDecimal configuredEmergencyStop;

    /* market data downloaded and stored during the engine lifetime */
    private BarSeries series;

    private IntelligentLimitAdapter intelligentLimitAdapter;
    private IntelligentPriceTracker priceTracker;
    private IntelligentStateTracker stateTracker;


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
        this.tradingApi = tradingApi;
        this.market = market;
        readStrategyConfig(config);
        this.intelligentLimitAdapter = new IntelligentLimitAdapter(config);
        BarSeries loadedSeries = JsonBarsSerializer.loadSeries("not_available.json");
        if (loadedSeries == null) {
            LOG.info(() -> "No series to be loaded found. Create a new series.");
            loadedSeries = new BaseBarSeriesBuilder().withName(market.getName() + "_" + System.currentTimeMillis()).build();
        }
        series = loadedSeries;
        priceTracker = new IntelligentPriceTracker(tradingApi, market, series);
        stateTracker = new IntelligentStateTracker(tradingApi, market, priceTracker, config);
        LOG.info(() -> "Trading Strategy initialised successfully!");
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
        logCurrentSellStatistics();
        stateTracker.trackRunningSellOrder(this::computeCurrentSellPrice, newState -> {
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

    private void logCurrentSellStatistics() throws TradingApiException, ExchangeNetworkException {
        BigDecimal currentMarketBidPrice = priceTracker.getBid();
        BigDecimal currentSellOrderPrice = stateTracker.getCurrentSellOrderPrice();
        BigDecimal breakEven = calculateBreakEven();

        BigDecimal aboveBreakEvenPriceLimit = calculateAboveBreakEvenPriceLimit();
        BigDecimal belowBreakEvenPriceLimit = calculateBelowBreakEvenPriceLimit();
        BigDecimal minimumAboveBreakEvenPriceLimit = calculateMinimumAboveBreakEvenPriceLimit(breakEven);
        BigDecimal currentBuyOrderPrice = stateTracker.getCurrentBuyOrderPrice();
        BigDecimal percentageChangeToBuyOrder = getPercentageChange(currentMarketBidPrice, currentBuyOrderPrice);
        BigDecimal percentageChangeToBreakEven = getPercentageChange(currentMarketBidPrice, breakEven);
        BigDecimal percentageChangeCurrentSellToMarket = getPercentageChange(currentSellOrderPrice, currentMarketBidPrice);
        BigDecimal percentageChangeCurrentSellToBreakEven = getPercentageChange(currentSellOrderPrice, breakEven);
        BigDecimal percentageChangeCurrentSellToBuy = getPercentageChange(currentSellOrderPrice, currentBuyOrderPrice);

        LOG.info(() -> market.getName() + " Current sell statistics: \n" +
                "######### SELL ORDER STATISTICS #########\n" +
                "current market price (last): " + priceTracker.getFormattedLast() + "\n" +
                "current market price (bid): " + priceTracker.getFormattedBid() + "\n" +
                "current market price (ask): " + priceTracker.getFormattedAsk() + "\n" +
                "current BUY order price: " + priceTracker.formatWithCounterCurrency(currentBuyOrderPrice) + "\n" +
                "current SELL order price: " + priceTracker.formatWithCounterCurrency(currentSellOrderPrice) + "\n" +
                "break even: " + priceTracker.formatWithCounterCurrency(breakEven) + "\n" +
                "-----------------------------------------\n" +
                "market change (bid) to buy price: " + DECIMAL_FORMAT.format(percentageChangeToBuyOrder) + "%\n" +
                "market change (bid) to break even: " + DECIMAL_FORMAT.format(percentageChangeToBreakEven) + "%\n" +
                "current sell price to buy price: " + DECIMAL_FORMAT.format(percentageChangeCurrentSellToBuy) + "%\n" +
                "current sell price to market (bid): " + DECIMAL_FORMAT.format(percentageChangeCurrentSellToMarket) + "%\n" +
                "current sell price to break even: " + DECIMAL_FORMAT.format(percentageChangeCurrentSellToBreakEven) + "%\n" +
                "-----------------------------------------\n" +
                "limit above break even: " + priceTracker.formatWithCounterCurrency(aboveBreakEvenPriceLimit) + "\n" +
                "limit minimum above break even: " + priceTracker.formatWithCounterCurrency(minimumAboveBreakEvenPriceLimit) + "\n" +
                "limit below break even: " + priceTracker.formatWithCounterCurrency(belowBreakEvenPriceLimit) + "\n" +
                "#########################################"

        );
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

    private BigDecimal getPercentageChange(BigDecimal newPrice, BigDecimal priceToCompareAgainst) {
        return newPrice.subtract(priceToCompareAgainst).divide(priceToCompareAgainst, 10, RoundingMode.HALF_UP).multiply(oneHundred);
    }

    private void executeBuyPhase() throws TradingApiException, ExchangeNetworkException, StrategyException {
        LOG.info(() -> market.getName() + " BUY phase - check if the market moved up.");

        if (marketMovedUp()) {
            LOG.info(() -> market.getName() + " BUY phase - The market moved up. Place a BUY order on the exchange -->");
            stateTracker.placeBuyOrder(this::getAmountOfPiecesToBuy);
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
                "\n * Current cleaned ask price (in " +currentTimesAboveLowestPriceNeeded+" ticks): " + priceTracker.formatWithCounterCurrency(cleanedMarketPrice) +
                "\n * Minimum seen ask price (in " +currentLowestPriceLookbackCount + " ticks): " + priceTracker.formatWithCounterCurrency(lowestAskPrice) +
                "\n * Gain needed from this minimum price: " + DECIMAL_FORMAT.format(currentPercentageGainNeededForBuy.multiply(oneHundred)) +
                "% = " + priceTracker.formatWithCounterCurrency(amountToMoveUp) +
                "\n * Current market above minimum: " + DECIMAL_FORMAT.format(percentageChangeMarketToMinimum) + "%" +
                "\n * Cleaned market above minimum ("+currentTimesAboveLowestPriceNeeded +" ticks): " + DECIMAL_FORMAT.format(percentageChangeCleanedMarketToMinimum) + "%\n" +
                "########################################");

        return cleanedMarketPrice.compareTo(goalToReach) > 0;
    }

    private BigDecimal calulateLowestAskPriceIn(int ticks) {
        int currentEndIndex = series.getEndIndex();
        Num result = series.getBar(currentEndIndex).getHighPrice();
        int currentBeginIndex = series.getBeginIndex();

        int spanStartIndex = currentEndIndex - ticks;
        int availableStartIndex = Math.max(currentBeginIndex, spanStartIndex);
        for(int i=availableStartIndex; i<currentEndIndex; i++) {
            result = series.getBar(i).getHighPrice().min(result);
        }
        return (BigDecimal)result.getDelegate();
    }



    private void executeSellPhase() throws TradingApiException, ExchangeNetworkException, StrategyException {
        LOG.info(() -> market.getName() + " SELL phase - create a SELL order for the last sucessfull BUY.");
       stateTracker.placeSellOrder(this::computeCurrentSellPrice);
    }

    private BigDecimal computeCurrentSellPrice() throws TradingApiException, ExchangeNetworkException {
        BigDecimal breakEven = calculateBreakEven();
        LOG.info(() -> market.getName() + " The calculated break even for selling would be '" + priceTracker.formatWithCounterCurrency(breakEven) + "'");
        BigDecimal maximalPriceLimitAboveBreakEven = calculateMaximalPriceLimitAboveBreakEven(breakEven);
        if (maximalPriceLimitAboveBreakEven.compareTo(breakEven) >= 0) {
            LOG.info(() -> market.getName() + " SELL phase - the above trailing gain is reached. Computed new SELL price is '" + priceTracker.formatWithCounterCurrency(maximalPriceLimitAboveBreakEven) + "' above the break even");
            return maximalPriceLimitAboveBreakEven;
        } else {
            BigDecimal belowBreakEvenPriceLimit = calculateBelowBreakEvenPriceLimit();
            LOG.info(() -> market.getName() + " SELL phase - still below break even. Computed new SELL price is '" + priceTracker.formatWithCounterCurrency(belowBreakEvenPriceLimit) + "' below the break even.");
            return belowBreakEvenPriceLimit;
        }
    }

    private BigDecimal calculateMaximalPriceLimitAboveBreakEven(BigDecimal breakEven) {
        BigDecimal aboveBreakEvenPriceLimit = calculateAboveBreakEvenPriceLimit();
        BigDecimal minimumAboveBreakEvenPriceLimit = calculateMinimumAboveBreakEvenPriceLimit(breakEven);
        return aboveBreakEvenPriceLimit.max(minimumAboveBreakEvenPriceLimit);
    }

    private BigDecimal calculateBelowBreakEvenPriceLimit() {
        BigDecimal distanceToCurrentMarketPrice = priceTracker.getBid().multiply(intelligentLimitAdapter.getCurrentSellStopLimitPercentageBelowBreakEven());
        return priceTracker.getBid().subtract(distanceToCurrentMarketPrice);
    }

    private BigDecimal calculateAboveBreakEvenPriceLimit() {
        return priceTracker.getBid().subtract(priceTracker.getBid().multiply(intelligentLimitAdapter.getCurrentSellStopLimitPercentageAboveBreakEven()));
    }

    private BigDecimal calculateMinimumAboveBreakEvenPriceLimit(BigDecimal breakEven) {
        BigDecimal currentSellStopLimitPercentageMinimumAboveBreakEven = intelligentLimitAdapter.getCurrentSellStopLimitPercentageMinimumAboveBreakEven();
        BigDecimal minimalDistanceToCurrentMarketPrice = priceTracker.getBid().subtract(priceTracker.getBid().multiply(currentSellStopLimitPercentageMinimumAboveBreakEven));
        BigDecimal minimalDistanceNeededToBreakEven = breakEven.add(breakEven.multiply(currentSellStopLimitPercentageMinimumAboveBreakEven));
        return minimalDistanceNeededToBreakEven.min(minimalDistanceToCurrentMarketPrice);

    }

    private BigDecimal calculateBreakEven() throws TradingApiException, ExchangeNetworkException {
        /* (p1 * (1+f)) / (1-f) <= p2 */
        BigDecimal buyFees = BigDecimal.ONE.add(tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(market.getId()));
        BigDecimal sellFees = BigDecimal.ONE.subtract(tradingApi.getPercentageOfSellOrderTakenForExchangeFee(market.getId()));

        BigDecimal totalBuy = stateTracker.getCurrentBuyOrderPrice().multiply(buyFees);
        BigDecimal estimatedBreakEven = totalBuy.divide(sellFees, 8, RoundingMode.HALF_UP);
        return estimatedBreakEven;
    }

    private BigDecimal getAmountOfPiecesToBuy() throws TradingApiException, ExchangeNetworkException, StrategyException {
        final BigDecimal balanceToUseForBuyOrder = getBalanceToUseForBuyOrder();
        LOG.info(
                () ->
                        market.getName()
                                + " Calculating amount of base currency ("
                                + market.getBaseCurrency()
                                + ") to buy for amount of counter currency "
                                + priceTracker.formatWithCounterCurrency(balanceToUseForBuyOrder));

        /*
         * Most exchanges (if not all) use 8 decimal places and typically round in favour of the
         * exchange. It's usually safest to round down the order quantity in your calculations.
         */
        final BigDecimal amountOfPiecesInBaseCurrencyToBuy = balanceToUseForBuyOrder.divide(priceTracker.getAsk(), 8, RoundingMode.HALF_DOWN);

        LOG.info(
                () ->
                        market.getName()
                                + " Amount of base currency ("
                                + market.getBaseCurrency()
                                + ") to BUY for "
                                + priceTracker.formatWithCounterCurrency(balanceToUseForBuyOrder)
                                + " based on last market trade price: "
                                + amountOfPiecesInBaseCurrencyToBuy);

        return amountOfPiecesInBaseCurrencyToBuy;
    }

    private BigDecimal getBalanceToUseForBuyOrder() throws ExchangeNetworkException, TradingApiException, StrategyException {
        if(true)
        return twentyFive;

        final BigDecimal currentBalance = priceTracker.getAvailableCounterCurrencyBalance();

        BigDecimal balanceAvailableForTrading = currentBalance.subtract(configuredEmergencyStop);
        if (balanceAvailableForTrading.compareTo(BigDecimal.ZERO) <= 0) {
            String errorMsg = "No balance available for trading. When substracting the emergency stop, the remaining balance is " + priceTracker.formatWithCounterCurrency(balanceAvailableForTrading);
            LOG.error(() -> market.getName() + errorMsg);
            throw new StrategyException(errorMsg);
        }
        LOG.info(() -> market.getName() + "Balance available after being reduced by the emergency stop: " + priceTracker.formatWithCounterCurrency(balanceAvailableForTrading));
        BigDecimal balanceToUseForBuyOrder = balanceAvailableForTrading.multiply(configuredPercentageOfCounterCurrencyBalanceToUse);
        LOG.info(() -> market.getName() + "Balance to be used for trading, taking into consideration the configured trading percentage of "
                + DECIMAL_FORMAT.format(configuredPercentageOfCounterCurrencyBalanceToUse) + ": " + priceTracker.formatWithCounterCurrency(balanceToUseForBuyOrder));
        return balanceToUseForBuyOrder;
    }

    private void readStrategyConfig(StrategyConfig config) {
        configuredPercentageOfCounterCurrencyBalanceToUse = StrategyConfigParser.readPercentageConfigValue(config, "percentage-of-counter-currency-balance-to-use");
        configuredEmergencyStop = StrategyConfigParser.readAmount(config, "configured-emergency-stop-balance");
    }
}
