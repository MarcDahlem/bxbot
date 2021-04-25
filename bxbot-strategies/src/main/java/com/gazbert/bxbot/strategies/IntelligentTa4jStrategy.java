package com.gazbert.bxbot.strategies;

import com.gazbert.bxbot.strategies.helper.*;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.TradingApiException;
import com.gazbert.bxbot.trading.api.util.ta4j.BreakEvenIndicator;
import com.gazbert.bxbot.trading.api.util.ta4j.BuyAndSellSignalsToChart;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Indicator;
import org.ta4j.core.Rule;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;
import org.ta4j.core.indicators.helpers.*;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;
import org.ta4j.core.rules.OverIndicatorRule;
import org.ta4j.core.rules.UnderIndicatorRule;

import java.math.BigDecimal;
import java.util.*;

@Component("intelligentTa4jStrategy") // used to load the strategy using Spring bean injection
public class IntelligentTa4jStrategy extends AbstractIntelligentStrategy {


    private BaseStrategy ta4jStrategy;
    private HighPriceIndicator askPriceIndicator;
    private final Collection<Integer> recordedSellIndices = new HashSet<>();
    private final Collection<Integer> recordedBuyIndeces = new HashSet<>();
    private BigDecimal buyFee;
    private BigDecimal sellFee;
    private Indicator<Num> buyIndicatorLong;
    private Indicator<Num> buyIndicatorShort;
    private Indicator<Num> sellIndicatorLong;
    private Indicator<Num> sellIndicatorShort;

    private void initTa4jStrategy() throws TradingApiException, ExchangeNetworkException {
        BarSeries series = priceTracker.getSeries();
        buyFee =tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(market.getId());
        sellFee = tradingApi.getPercentageOfSellOrderTakenForExchangeFee(market.getId());

        ClosePriceIndicator closePriceIndicator = new ClosePriceIndicator(series);
        LowPriceIndicator bidPriceIndicator = new LowPriceIndicator(series);
        askPriceIndicator = new HighPriceIndicator(series);

        StochasticOscillatorKIndicator stochasticOscillaltorK = new StochasticOscillatorKIndicator(series, 14);
        MACDIndicator macd = new MACDIndicator(closePriceIndicator, 9, 26);
        EMAIndicator emaMacd = new EMAIndicator(macd, 18);

        BigDecimal buyFeeFactor = BigDecimal.ONE.add(buyFee);
        BigDecimal sellFeeFactor = BigDecimal.ONE.subtract(sellFee);

        buyIndicatorLong = new EMAIndicator(bidPriceIndicator, 52);
        buyIndicatorShort = TransformIndicator.multiply(new EMAIndicator(bidPriceIndicator, 9), sellFeeFactor);

        sellIndicatorLong = new EMAIndicator(askPriceIndicator, 52);
        sellIndicatorShort = TransformIndicator.multiply(new EMAIndicator(askPriceIndicator, 9), buyFeeFactor);

        Rule entryRule = new CrossedUpIndicatorRule(buyIndicatorShort, buyIndicatorLong) // Trend
                /*.and(new UnderIndicatorRule(stochasticOscillaltorK, 20))*/ // Signal 1
                /*.and(new OverIndicatorRule(macd, emaMacd))*/; // Signal 2

        Rule exitRule = new CrossedDownIndicatorRule(sellIndicatorShort, sellIndicatorLong) // Trend
                /*.and(new OverIndicatorRule(stochasticOscillaltorK, 80))*( // Signal 1
                /*.and(new UnderIndicatorRule(macd, emaMacd))*/; // Signal 2
        ta4jStrategy = new BaseStrategy("Intelligent Ta4j", entryRule, exitRule);
    }

    @Override
    protected void onClose() {
        Map<Indicator<Num>, String> indicators = new HashMap<>();
        //indicators.add(stochasticOscillaltorK);
        //indicators.add(macd);
        //indicators.add(emaMacd);
        //indicators.put(shortTimeEma, "s-ema");
        //indicators.put(longTimeEma, "l-ema");
        indicators.put(buyIndicatorShort, "buy short");
        indicators.put(buyIndicatorLong, "buy long");
        indicators.put(sellIndicatorShort, "sell short");
        indicators.put(sellIndicatorLong, "sell long");
        BreakEvenIndicator breakEvenIndicator = new BreakEvenIndicator(askPriceIndicator, buyFee, sellFee, recordedBuyIndeces, recordedSellIndices);
        indicators.put(breakEvenIndicator, "break even");
        //indicators.put(shortTimeEmaAsk, "s-ema (ask)");
        //indicators.put(longTimeEmaAsk, "l-ema (ask)");
        //indicators.put(shortTimeEmaBid, "s-ema (bid)");
        //indicators.put(longTimeEmaBid, "l-ema (bid)");

        BuyAndSellSignalsToChart.printSeries(priceTracker.getSeries(), ta4jStrategy, indicators);
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
                return BigDecimal.ZERO;
            }

            @Override
            public BigDecimal getCurrentSellStopLimitPercentageAboveBreakEven() {
                return BigDecimal.ZERO;
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
    protected boolean marketMovedUp() throws TradingApiException, ExchangeNetworkException {
        if (ta4jStrategy == null) {
            initTa4jStrategy();
        }
        boolean result = ta4jStrategy.shouldEnter(priceTracker.getSeries().getEndIndex());
        if (result) {
            recordedBuyIndeces.add(priceTracker.getSeries().getEndIndex());
        }
        return result;
    }

    @Override
    protected boolean marketMovedDown() throws TradingApiException, ExchangeNetworkException {
        if (ta4jStrategy == null) {
            initTa4jStrategy();
        }
        boolean result = ta4jStrategy.shouldExit(priceTracker.getSeries().getEndIndex());
        if (result) {
            recordedSellIndices.add(priceTracker.getSeries().getEndIndex());
        }
        return result;
    }
}
