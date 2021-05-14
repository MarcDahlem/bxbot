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

import com.gazbert.bxbot.strategies.helper.IntelligentBuyPriceCalculator;
import com.gazbert.bxbot.strategies.helper.IntelligentSellPriceCalculator;
import com.gazbert.bxbot.strategies.helper.IntelligentStateTracker;
import com.gazbert.bxbot.strategies.helper.StaticBuyPriceCalculator;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.TradingApiException;
import com.gazbert.bxbot.trading.api.util.ta4j.CombineIndicator;
import com.gazbert.bxbot.trading.api.util.ta4j.SellIndicator;
import com.gazbert.bxbot.trading.api.util.ta4j.Ta4j2Chart;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowestValueIndicator;
import org.ta4j.core.indicators.helpers.TransformIndicator;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.Collection;
import java.util.LinkedList;

@Component("intelligentTrailingStopStrategy") // used to load the strategy using Spring bean injection
public class IntelligentTrailingStopStrategy extends AbstractIntelligentStrategy {

    private static final Logger LOG = LogManager.getLogger();

    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#.########");
    private static final BigDecimal oneHundred = new BigDecimal("100");
    private IntelligentTrailingStopConfigParams intelligentTrailingStopConfigParams;

    protected boolean marketMovedUp() throws TradingApiException, ExchangeNetworkException {
        BigDecimal currentPercentageGainNeededForBuy = intelligentTrailingStopConfigParams.getCurrentPercentageGainNeededForBuy();
        int currentLowestPriceLookbackCount = intelligentTrailingStopConfigParams.getCurrentLowestPriceLookbackCount();
        BigDecimal lowestAskPrice = calulateLowestAskPriceIn(currentLowestPriceLookbackCount);
        int currentTimesAboveLowestPriceNeeded = intelligentTrailingStopConfigParams.getCurrentTimesAboveLowestPriceNeeded();
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

    @Override
    protected boolean marketMovedDown() throws TradingApiException, ExchangeNetworkException {
        return true; //always calculate new sell prices
    }

    @Override
    protected void botWillShutdown() {
    }

    @Override
    protected void botWillStartup(StrategyConfig config) {

    }

    @Override
    protected Collection<? extends Ta4j2Chart.ChartIndicatorConfig> createStrategySpecificLiveChartIndicators() throws TradingApiException, ExchangeNetworkException {
        SellIndicator belowBreakEvenIndicator = SellIndicator.createSellLimitIndicator(priceTracker.getSeries(), intelligentTrailingStopConfigParams.getCurrentSellStopLimitPercentageBelowBreakEven(), stateTracker.getBreakEvenIndicator());
        SellIndicator aboveBreakEvenIndicator = SellIndicator.createSellLimitIndicator(priceTracker.getSeries(), intelligentTrailingStopConfigParams.getCurrentSellStopLimitPercentageAboveBreakEven(), stateTracker.getBreakEvenIndicator());
        Indicator<Num> minAboveBreakEvenIndicator = createMinAboveBreakEvenIndicator();
        Indicator<Num> longBuyLowPrice = new LowestValueIndicator(new HighPriceIndicator(priceTracker.getSeries()), intelligentTrailingStopConfigParams.getCurrentLowestPriceLookbackCount() +1);
        Indicator<Num> shortBuyLowPrice = new LowestValueIndicator(new HighPriceIndicator(priceTracker.getSeries()), intelligentTrailingStopConfigParams.getCurrentTimesAboveLowestPriceNeeded() +1);
        Indicator<Num> gainLine = TransformIndicator.multiply(longBuyLowPrice, BigDecimal.ONE.add(intelligentTrailingStopConfigParams.getCurrentPercentageGainNeededForBuy()));
        LinkedList<Ta4j2Chart.ChartIndicatorConfig> result = new LinkedList<>();
        result.add(new Ta4j2Chart.ChartIndicatorConfig(aboveBreakEvenIndicator, "limit above BE", Ta4j2Chart.SELL_LIMIT_2_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(minAboveBreakEvenIndicator, "limit min above BE", Ta4j2Chart.SELL_LIMIT_1_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(belowBreakEvenIndicator, "limit below BE", Ta4j2Chart.SELL_LIMIT_3_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(longBuyLowPrice, "lowest (" + intelligentTrailingStopConfigParams.getCurrentLowestPriceLookbackCount() + ")", Ta4j2Chart.BUY_LONG_LOOKBACK_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(shortBuyLowPrice, "lowest (" + intelligentTrailingStopConfigParams.getCurrentTimesAboveLowestPriceNeeded() + ")", Ta4j2Chart.BUY_SHORT_LOOKBACK_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(gainLine, "buy distance", Ta4j2Chart.BUY_TRIGGER_COLOR));
        return result;
    }

    private Indicator<Num> createMinAboveBreakEvenIndicator() throws TradingApiException, ExchangeNetworkException {
        SellIndicator limitIndicator = SellIndicator.createSellLimitIndicator(priceTracker.getSeries(), intelligentTrailingStopConfigParams.getCurrentSellStopLimitPercentageMinimumAboveBreakEven(), stateTracker.getBreakEvenIndicator());
        BigDecimal minimumAboveBreakEvenAsFactor = BigDecimal.ONE.subtract(intelligentTrailingStopConfigParams.getCurrentSellStopLimitPercentageMinimumAboveBreakEven());
        TransformIndicator minimalDistanceNeededToBreakEven = TransformIndicator.divide(stateTracker.getBreakEvenIndicator(), minimumAboveBreakEvenAsFactor);
        return CombineIndicator.min(limitIndicator, minimalDistanceNeededToBreakEven);
    }

    @Override
    protected IntelligentStateTracker.OrderPriceCalculator createSellPriceCalculator(StrategyConfig config) {
        return new IntelligentSellPriceCalculator(priceTracker, stateTracker, new IntelligentSellPriceCalculator.IntelligentSellPriceParameters() {
            @Override
            public BigDecimal getBuyFee() throws TradingApiException, ExchangeNetworkException {
                return tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(market.getId());
            }

            @Override
            public BigDecimal getSellFee() throws TradingApiException, ExchangeNetworkException {
                return tradingApi.getPercentageOfSellOrderTakenForExchangeFee(market.getId());
            }

            @Override
            public BigDecimal getCurrentSellStopLimitPercentageBelowBreakEven() {
                return intelligentTrailingStopConfigParams.getCurrentSellStopLimitPercentageBelowBreakEven();
            }

            @Override
            public BigDecimal getCurrentSellStopLimitPercentageAboveBreakEven() {
                return intelligentTrailingStopConfigParams.getCurrentSellStopLimitPercentageAboveBreakEven();
            }

            @Override
            public BigDecimal getCurrentSellStopLimitPercentageMinimumAboveBreakEven() {
                return intelligentTrailingStopConfigParams.getCurrentSellStopLimitPercentageMinimumAboveBreakEven();
            }
        });
    }

    @Override
    protected IntelligentStateTracker.OrderPriceCalculator createBuyPriceCalculator(StrategyConfig config) {
        IntelligentStateTracker.OrderPriceCalculator result = new IntelligentBuyPriceCalculator(market, priceTracker, config);
        //result = new StaticBuyPriceCalculator(market, priceTracker, new BigDecimal("25")); // TODO remove
        return result;
    }

    @Override
    protected IntelligentTrailingStopConfigParams createTradesObserver(StrategyConfig config) {
        if (intelligentTrailingStopConfigParams == null) {
            intelligentTrailingStopConfigParams = new IntelligentTrailingStopConfigParams(config);
        }
        return intelligentTrailingStopConfigParams;
    }

    private BigDecimal getPercentageChange(BigDecimal newPrice, BigDecimal priceToCompareAgainst) {
        return newPrice.subtract(priceToCompareAgainst).divide(priceToCompareAgainst, 10, RoundingMode.HALF_UP).multiply(oneHundred);
    }

    private BigDecimal calulateLowestAskPriceIn(int ticks) throws TradingApiException, ExchangeNetworkException {
        BarSeries series = priceTracker.getSeries();
        int currentEndIndex = series.getEndIndex();
        Num result = series.getBar(currentEndIndex).getHighPrice();
        int currentBeginIndex = series.getBeginIndex();

        int spanStartIndex = currentEndIndex - ticks+1;
        int availableStartIndex = Math.max(currentBeginIndex, spanStartIndex);
        Integer lastRecordedSellIndex = stateTracker.getBreakEvenIndicator().getLastRecordedSellIndex();

        int startIndexRegardingSells = availableStartIndex;
        if (lastRecordedSellIndex != null) {
            startIndexRegardingSells = Math.max(availableStartIndex, lastRecordedSellIndex);
        }
        for (int i = startIndexRegardingSells; i <= currentEndIndex; i++) {
            result = series.getBar(i).getHighPrice().min(result);
        }
        return (BigDecimal) result.getDelegate();
    }

    @Override
    protected Collection<? extends Ta4j2Chart.ChartIndicatorConfig> createStrategySpecificOverviewChartIndicators() throws TradingApiException, ExchangeNetworkException {
        return createStrategySpecificLiveChartIndicators();
    }
}
