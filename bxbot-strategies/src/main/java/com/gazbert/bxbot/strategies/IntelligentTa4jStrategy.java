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

import java.math.BigDecimal;
import java.util.*;

@Component("intelligentTa4jStrategy") // used to load the strategy using Spring bean injection
public class IntelligentTa4jStrategy extends AbstractIntelligentStrategy {


    private BaseStrategy ta4jStrategy;
    private StochasticOscillatorKIndicator stochasticOscillaltorK;
    private MACDIndicator macd;
    private EMAIndicator emaMacd;
    private EMAIndicator shortTimeEma;
    private EMAIndicator longTimeEma;
    private EMAIndicator shortTimeEmaLong;
    private EMAIndicator longTimeEmaLong;
    private EMAIndicator shortTimeEmaBid;
    private EMAIndicator longTimeEmaBid;
    private EMAIndicator shortTimeEmaAsk;
    private EMAIndicator longTimeEmaAsk;
    private TransformIndicator longTimeEmaLongBuyfee;
    private TransformIndicator shortTimeEmaLongBuyfee;
    private TransformIndicator shortTimeEmaLongSellFee;
    private TransformIndicator longTimeEmaLongSellFee;
    private HighPriceIndicator askPriceIndicator;
    private Collection<Integer> recordedSellIndices = new HashSet<>();
    private Collection<Integer> recordedBuyIndeces = new HashSet<>();
    private BigDecimal buyFee;
    private BigDecimal sellFee;

    private void initTa4jStrategy() throws TradingApiException, ExchangeNetworkException {
        BarSeries series = priceTracker.getSeries();
        ClosePriceIndicator closePriceIndicator = new ClosePriceIndicator(series);
        LowPriceIndicator bidPriceIndicator = new LowPriceIndicator(series);
        askPriceIndicator = new HighPriceIndicator(series);

        stochasticOscillaltorK = new StochasticOscillatorKIndicator(series, 14);
        macd = new MACDIndicator(closePriceIndicator, 9, 26);
        emaMacd = new EMAIndicator(macd, 18);

        shortTimeEma = new EMAIndicator(closePriceIndicator, 9);
        longTimeEma = new EMAIndicator(closePriceIndicator, 26);

        shortTimeEmaLong = new EMAIndicator(closePriceIndicator, 38);
        longTimeEmaLong = new EMAIndicator(closePriceIndicator, 104);

        shortTimeEmaBid = new EMAIndicator(bidPriceIndicator, 9);
        longTimeEmaBid = new EMAIndicator(bidPriceIndicator, 26);

        shortTimeEmaAsk = new EMAIndicator(askPriceIndicator, 9);
        longTimeEmaAsk = new EMAIndicator(askPriceIndicator, 26);

        buyFee =tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(market.getId());
        sellFee = tradingApi.getPercentageOfSellOrderTakenForExchangeFee(market.getId());

        BigDecimal buyFeeFactor = BigDecimal.ONE.add(buyFee);
        BigDecimal sellFeeFactor = BigDecimal.ONE.subtract(sellFee);

        shortTimeEmaLongBuyfee = TransformIndicator.multiply(shortTimeEma, buyFeeFactor);
        shortTimeEmaLongSellFee = TransformIndicator.multiply(shortTimeEma, sellFeeFactor);

        longTimeEmaLongBuyfee = TransformIndicator.multiply(longTimeEmaLong, buyFeeFactor);
        longTimeEmaLongSellFee = TransformIndicator.multiply(longTimeEmaLong, sellFeeFactor);

        Rule entryRule = new CrossedUpIndicatorRule(shortTimeEmaLongSellFee, longTimeEmaLong) // Trend
                /*.and(new CrossedDownIndicatorRule(stochasticOscillaltorK, 20)) // Signal 1
                .and(new OverIndicatorRule(macd, emaMacd))*/; // Signal 2

        Rule exitRule = new CrossedDownIndicatorRule(shortTimeEmaLongBuyfee, longTimeEmaLong) // Trend
                /*.and(new CrossedUpIndicatorRule(stochasticOscillaltorK, 80)) // Signal 1
                .and(new UnderIndicatorRule(macd, emaMacd))*/; // Signal 2
        ta4jStrategy = new BaseStrategy(entryRule, exitRule);
    }

    @Override
    protected void onClose() {
        Map<Indicator<Num>, String> indicators = new HashMap<>();
        //indicators.add(stochasticOscillaltorK);
        //indicators.add(macd);
        //indicators.add(emaMacd);
        //indicators.put(shortTimeEma, "s-ema");
        //indicators.put(longTimeEma, "l-ema");
        indicators.put(shortTimeEmaLong, "s-ema (long)");
        indicators.put(longTimeEmaLong, "l-ema (long)");
        indicators.put(longTimeEmaLongBuyfee, "l-ema (buy)");
        indicators.put(longTimeEmaLongSellFee, "l-ema (sell)");
        indicators.put(shortTimeEmaLongSellFee, "s-ema (sell)");
        indicators.put(shortTimeEmaLongBuyfee, "s-ema (Buy)");
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
