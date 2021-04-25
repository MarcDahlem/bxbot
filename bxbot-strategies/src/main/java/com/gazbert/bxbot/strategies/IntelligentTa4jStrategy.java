package com.gazbert.bxbot.strategies;

import com.gazbert.bxbot.strategies.helper.IntelligentBuyPriceCalculator;
import com.gazbert.bxbot.strategies.helper.IntelligentSellPriceCalculator;
import com.gazbert.bxbot.strategies.helper.IntelligentStateTracker;
import com.gazbert.bxbot.strategies.helper.StaticBuyPriceCalculator;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.TradingApiException;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Rule;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;
import org.ta4j.core.rules.OverIndicatorRule;
import org.ta4j.core.rules.UnderIndicatorRule;

import java.math.BigDecimal;

@Component("intelligentTa4jStrategy") // used to load the strategy using Spring bean injection
public class IntelligentTa4jStrategy extends AbstractIntelligentStrategy {


    private BaseStrategy ta4jStrategy;

    private void initTa4jStrategy() {
        BarSeries series = priceTracker.getSeries();
        ClosePriceIndicator closePriceIndicator = new ClosePriceIndicator(series);

        StochasticOscillatorKIndicator stochasticOscillaltorK = new StochasticOscillatorKIndicator(series, 14);
        MACDIndicator macd = new MACDIndicator(closePriceIndicator, 9, 26);
        EMAIndicator emaMacd = new EMAIndicator(macd, 18);

        EMAIndicator shortTimeEma = new EMAIndicator(closePriceIndicator, 9);
        EMAIndicator longTimeEma = new EMAIndicator(closePriceIndicator, 26);

        Rule entryRule = new OverIndicatorRule(shortTimeEma, longTimeEma) // Trend
                .and(new CrossedDownIndicatorRule(stochasticOscillaltorK, 20)) // Signal 1
                .and(new OverIndicatorRule(macd, emaMacd)); // Signal 2

        Rule exitRule = new UnderIndicatorRule(shortTimeEma, longTimeEma) // Trend
                .and(new CrossedUpIndicatorRule(stochasticOscillaltorK, 80)) // Signal 1
                .and(new UnderIndicatorRule(macd, emaMacd)); // Signal 2
        ta4jStrategy = new BaseStrategy(entryRule, exitRule);
    }

    @Override
    protected IntelligentStateTracker.OrderPriceCalculator createSellPriceCalculator(StrategyConfig config) {
        return new IntelligentSellPriceCalculator(priceTracker, new IntelligentSellPriceCalculator.IntelligentSellPriceParameters() {

            private final BigDecimal half = new BigDecimal("0.005");
            private final BigDecimal oneAndHalf = new BigDecimal("0.01");

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
                return half;
            }

            @Override
            public BigDecimal getCurrentSellStopLimitPercentageAboveBreakEven() {
                return oneAndHalf;
            }

            @Override
            public BigDecimal getCurrentSellStopLimitPercentageMinimumAboveBreakEven() {
                return half;
            }
        });
    }

    @Override
    protected IntelligentStateTracker.OrderPriceCalculator createBuyPriceCalculator(StrategyConfig config) {
        IntelligentStateTracker.OrderPriceCalculator result = new IntelligentBuyPriceCalculator(market, priceTracker, config);
        result = new StaticBuyPriceCalculator(market, priceTracker, new BigDecimal("25")); // TODO remove
        return result;
    }

    @Override
    protected IntelligentStateTracker.OnTradeSuccessfullyClosedListener createTradesObserver(StrategyConfig config) {
        return new IntelligentStateTracker.OnTradeSuccessfullyClosedListener() {
            private BigDecimal overallProfit = BigDecimal.ZERO;
            private int amountOfTrades = 0;

            @Override
            public void onTradeCloseSuccess(BigDecimal profit) {
                LOG.info("New profit received: " + priceTracker.formatWithCounterCurrency(profit));
                overallProfit = overallProfit.add(profit);
                LOG.info("New overall profit: " + priceTracker.formatWithCounterCurrency(overallProfit));
                amountOfTrades++;
                LOG.info("New amount of trades: " + amountOfTrades);
            }

            @Override
            public void logStatistics() {
                LOG.info("Overall profit: " + priceTracker.formatWithCounterCurrency(overallProfit));
                LOG.info("Amount of trades: " + amountOfTrades);
            }
        };
    }

    @Override
    protected boolean marketMovedUp() {
        if (ta4jStrategy == null) {
            initTa4jStrategy();
        }
        return ta4jStrategy.shouldEnter(priceTracker.getSeries().getEndIndex());
    }
}
