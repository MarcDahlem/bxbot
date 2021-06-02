package com.gazbert.bxbot.strategies;

import static com.gazbert.bxbot.trading.api.util.ta4j.CombineIndicator.divide;
import static com.gazbert.bxbot.trading.api.util.ta4j.CombineIndicator.plus;
import static com.gazbert.bxbot.trading.api.util.ta4j.CombineIndicator.minus;
import static com.gazbert.bxbot.trading.api.util.ta4j.CombineIndicator.multiply;
import static com.gazbert.bxbot.trading.api.util.ta4j.MarketEnterType.LONG_POSITION;
import static com.gazbert.bxbot.trading.api.util.ta4j.MarketEnterType.SHORT_POSITION;
import static org.ta4j.core.indicators.helpers.TransformIndicator.multiply;
import static org.ta4j.core.indicators.helpers.TransformIndicator.minus;
import static org.ta4j.core.indicators.helpers.TransformIndicator.plus;

import com.gazbert.bxbot.strategies.helper.IntelligentEnterPriceCalculator;
import com.gazbert.bxbot.strategies.helper.IntelligentStateTracker;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.TradingApiException;
import com.gazbert.bxbot.trading.api.util.ta4j.*;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Optional;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.Rule;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.ConstantIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.HighestValueIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.indicators.helpers.LowestValueIndicator;
import org.ta4j.core.indicators.helpers.TransformIndicator;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.IsEqualRule;
import org.ta4j.core.rules.OverIndicatorRule;
import org.ta4j.core.rules.UnderIndicatorRule;

@Component("intelligentHiddenDivergenceTa4jStrategy")
// used to load the strategy using Spring bean injection
@Scope("prototype") // create always a new instance if it is injected
public class IntelligentHiddenDivergenceTa4jStrategy extends AbstractIntelligentStrategy {
    private Indicator<Num> longEma;
    private Indicator<Num> shortEma;
    private Indicator<Num> rsi;
    private MovingPivotPointIndicator lastHigh;
    private MovingPivotPointIndicator lastLow;
    private Indicator<Num> emaUpTrendLine;
    private Indicator<Num> emaDownTrendLine;
    private MovingPivotPointIndicator rsiAtLastHigh;
    private MovingPivotPointIndicator rsiAtLastLow;
    private Indicator<Num> enterPriceIndicator;
    private Rule longEntryRule;
    private Rule shortEntryRule;
    private Indicator<Num> stopLoss;
    private Indicator<Num> exitTakeProfitCalculator;

    @Override
    protected void botWillStartup(StrategyConfig config) throws TradingApiException, ExchangeNetworkException {
        initTa4jStrategy();
    }

    private void initTa4jStrategy() throws TradingApiException, ExchangeNetworkException {
        BarSeries series = priceTracker.getSeries();
        ClosePriceIndicator closePriceIndicator = new ClosePriceIndicator(series);
        int i = 138;
        int j = 77;

        int pivotCalculationFrame = 11;

        longEma = new EMAIndicator(closePriceIndicator, i);
        shortEma = new EMAIndicator(closePriceIndicator, j);
        rsi = new RSIIndicator(new ClosePriceIndicator(priceTracker.getSeries()), 14);
        lastHigh = new HighestPivotPointIndicator(priceTracker.getSeries());
        lastLow = new LowestPivotPointIndicator(priceTracker.getSeries());
        lastHigh.setOppositPivotIndicator(lastLow);
        lastLow.setOppositPivotIndicator(lastHigh);
        ATRIndicator trueRangeIndicator = new ATRIndicator(priceTracker.getSeries(), 14);
        TransformIndicator trueRangeFactor = multiply(trueRangeIndicator, 2);
        emaUpTrendLine = plus(longEma, trueRangeFactor);
        emaDownTrendLine = minus(longEma, trueRangeFactor);

        rsiAtLastHigh = new HighestPivotPointIndicator(priceTracker.getSeries(), rsi);
        rsiAtLastLow = new LowestPivotPointIndicator(priceTracker.getSeries(), rsi);
        rsiAtLastHigh.setOppositPivotIndicator(rsiAtLastLow);
        rsiAtLastLow.setOppositPivotIndicator(rsiAtLastHigh);

        HighestPivotPointIndicator secondLastHigh = new HighestPivotPointIndicator(series, new DelayIndicator(lastHigh, 1));
        LowestPivotPointIndicator secondLastLow = new LowestPivotPointIndicator(series, new DelayIndicator(lastLow, 1));
        secondLastHigh.setOppositPivotIndicator(secondLastLow);
        secondLastLow.setOppositPivotIndicator(secondLastHigh);

        HighestPivotPointIndicator rsiAtSecondLastHigh = new HighestPivotPointIndicator(series, new DelayIndicator(rsiAtLastHigh, 1));
        LowestPivotPointIndicator rsiAtSecondLastLow = new LowestPivotPointIndicator(series, new DelayIndicator(rsiAtLastLow, 1));
        rsiAtSecondLastHigh.setOppositPivotIndicator(rsiAtSecondLastLow);
        rsiAtSecondLastLow.setOppositPivotIndicator(rsiAtSecondLastHigh);

        Rule upTrend = new OverIndicatorRule(shortEma, emaUpTrendLine);
        Rule priceOverLongReversalArea = new OverIndicatorRule(closePriceIndicator, emaUpTrendLine);
        Rule lowPriceMovesUp = new OverIndicatorRule(lastLow, secondLastLow);
        Rule oversoldIndicatorMovesDown = new UnderIndicatorRule(rsiAtLastLow, rsiAtSecondLastLow);
        Rule lastHighIsInFrame = new IsEqualRule(lastHigh, new HighestValueIndicator(new HighPriceIndicator(series), pivotCalculationFrame+1));
        Rule lastLowIsInFrame = new IsEqualRule(lastLow, new LowestValueIndicator(new LowPriceIndicator(series), pivotCalculationFrame+1));

        longEntryRule = upTrend.and(priceOverLongReversalArea).and(lowPriceMovesUp).and(oversoldIndicatorMovesDown).and(lastLowIsInFrame);
        enterPriceIndicator = ExitIndicator.createEnterPriceIndicator(stateTracker.getBreakEvenIndicator());

        Rule downTrend = new UnderIndicatorRule(shortEma, emaDownTrendLine);
        Rule priceUnderLongReversalArea = new UnderIndicatorRule(closePriceIndicator, emaDownTrendLine);
        Rule highPriceMovesDown = new UnderIndicatorRule(lastHigh, secondLastHigh);
        Rule oversoldIndicatorMovesUp = new OverIndicatorRule(rsiAtLastHigh, rsiAtSecondLastHigh);

        shortEntryRule = downTrend.and(priceUnderLongReversalArea).and(highPriceMovesDown).and(oversoldIndicatorMovesUp).and(lastHighIsInFrame);

        createPublicExitIndicators();
    }

    @Override
    protected Collection<Ta4j2Chart.ChartIndicatorConfig> createStrategySpecificLiveChartIndicators() {
        LinkedList<Ta4j2Chart.ChartIndicatorConfig> result = new LinkedList<>();
        Ta4j2Chart.YAxisGroupConfig rsiYAxisConfig = new Ta4j2Chart.YAxisGroupConfig("RSI", 1, Ta4j2Chart.AREA_COLOR_1);
        result.add(new Ta4j2Chart.ChartIndicatorConfig(rsi, "RSI", Ta4j2Chart.AREA_COLOR_LINE_1, rsiYAxisConfig));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(
                new ConstantIndicator<>(priceTracker.getSeries(), priceTracker.getSeries().numOf(80)),
                "RSI (80)",
                Ta4j2Chart.AREA_COLOR_LINE_1,
                rsiYAxisConfig));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(
                new ConstantIndicator<>(priceTracker.getSeries(), priceTracker.getSeries().numOf(70)),
                "RSI (70)",
                Ta4j2Chart.AREA_COLOR_LINE_1,
                rsiYAxisConfig));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(
                new ConstantIndicator<>(priceTracker.getSeries(), priceTracker.getSeries().numOf(30)),
                "RSI (30)",
                Ta4j2Chart.AREA_COLOR_LINE_1,
                rsiYAxisConfig));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(
                new ConstantIndicator<>(priceTracker.getSeries(), priceTracker.getSeries().numOf(20)),
                "RSI (20)",
                Ta4j2Chart.AREA_COLOR_LINE_1,
                rsiYAxisConfig));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(shortEma, "short trend", Ta4j2Chart.BUY_SHORT_LOOKBACK_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(longEma, "long trend", Ta4j2Chart.BUY_LONG_LOOKBACK_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(lastHigh, "last high", Ta4j2Chart.ASK_PRICE_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(rsiAtLastHigh, "rsi at last high", Ta4j2Chart.SELL_LIMIT_1_COLOR, rsiYAxisConfig));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(lastLow, "last low", Ta4j2Chart.BID_PRICE_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(rsiAtLastLow, "rsi at last low", Ta4j2Chart.SELL_CURRENT_LIMIT_COLOR, rsiYAxisConfig));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(emaUpTrendLine, "+ATR14", Ta4j2Chart.BUY_TRIGGER_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(emaDownTrendLine, "-ATR14", Ta4j2Chart.SELL_LIMIT_3_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(enterPriceIndicator, "enter price", Ta4j2Chart.SELL_LIMIT_1_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(exitTakeProfitCalculator, "take profit", Ta4j2Chart.SELL_LIMIT_2_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(stopLoss, "stop loss", Ta4j2Chart.SELL_LIMIT_3_COLOR));

        return result;
    }

    @Override
    protected IntelligentStateTracker.OrderPriceCalculator createExitPriceCalculator(StrategyConfig config) throws TradingApiException, ExchangeNetworkException {
        return new IntelligentStateTracker.OrderPriceCalculator() {
            private OverIndicatorRule profitGainLongReached;
            private UnderIndicatorRule profitGainShortReached;
            private ClosePriceIndicator closePriceIndicator;
            private boolean initialized;

            @Override
            public BigDecimal calculate(MarketEnterType marketEnterType) throws TradingApiException, ExchangeNetworkException, StrategyException {
                initExitRules();
                int currentIndex = priceTracker.getSeries().getEndIndex();
                int lastEntryIndex = stateTracker.getBreakEvenIndicator().getLastRecordedEntryIndex();
                int checkIndex = lastEntryIndex == currentIndex ? currentIndex : currentIndex - 1;

                if (marketEnterType.equals(LONG_POSITION) && profitGainLongReached.isSatisfied(currentIndex)) {
                    return (BigDecimal) closePriceIndicator.getValue(currentIndex).getDelegate();
                }

                if (marketEnterType.equals(SHORT_POSITION) && profitGainShortReached.isSatisfied(currentIndex)) {
                    return (BigDecimal) closePriceIndicator.getValue(currentIndex).getDelegate();
                }

                return (BigDecimal) stopLoss.getValue(checkIndex).getDelegate();
            }

            private void initExitRules() throws TradingApiException, ExchangeNetworkException {
                if (!initialized) {
                    final BarSeries series = priceTracker.getSeries();
                    closePriceIndicator = new ClosePriceIndicator(series);

                    profitGainLongReached = new OverIndicatorRule(closePriceIndicator, exitTakeProfitCalculator);
                    profitGainShortReached = new UnderIndicatorRule(closePriceIndicator, exitTakeProfitCalculator);

                    initialized = true;
                }
            }

            @Override
            public void logStatistics(MarketEnterType marketEnterType) throws TradingApiException, ExchangeNetworkException, StrategyException {

            }
        };
    }

    private void createPublicExitIndicators() throws TradingApiException, ExchangeNetworkException {
        BarSeries series = priceTracker.getSeries();
        ATRIndicator trueRangeIndicator = new ATRIndicator(series, 14);
        Number profitGainRatio = 2;
        Number stoplossAtrRatio = 7;
        TransformIndicator trueRangeFactor = multiply(trueRangeIndicator, stoplossAtrRatio);

        stopLoss = new ExitIndicator(series, stateTracker.getBreakEvenIndicator(),
                entryIndex -> enterType -> index -> {
                    if (enterType.equals(LONG_POSITION)) {
                        return new ConstantIndicator<>(series, minus(enterPriceIndicator, trueRangeFactor).getValue(entryIndex));
                    } else {
                        return new ConstantIndicator<>(series, plus(enterPriceIndicator, trueRangeFactor).getValue(entryIndex));
                    }
                });
        CombineIndicator factorBetweenStopLossAndEnterPrice = divide(enterPriceIndicator, stopLoss); // eg. 5/4==1.25 long or 4/5 = 0.8 short
        TransformIndicator percentageGainOrLossOfStopLoss = minus(factorBetweenStopLossAndEnterPrice, 1); // 0.25 long or -0.2 short
        Indicator<Num> percentageGainOrLossToReach = multiply(percentageGainOrLossOfStopLoss, profitGainRatio); // 0.25*2 = 0.5 long or -0.2*2 = -0.4
        TransformIndicator factorToReachForGainOrLoss = plus(percentageGainOrLossToReach, 1); // 1.5 long or 0.6 short
        exitTakeProfitCalculator = multiply(factorToReachForGainOrLoss, enterPriceIndicator); // 5*1.5 = 7.5 Long or 5*0.6 = 3 short
    }

    @Override
    protected IntelligentStateTracker.OrderPriceCalculator createEnterPriceCalculator(StrategyConfig config) {
        return new IntelligentEnterPriceCalculator(market, priceTracker, config);
    }

    @Override
    protected IntelligentStateTracker.OnTradeSuccessfullyClosedListener createTradesObserver(StrategyConfig config) {
        return new IntelligentTradeTracker();
    }

    @Override
    protected Optional<MarketEnterType> shouldEnterMarket() {
        boolean resultLong = longEntryRule.isSatisfied(priceTracker.getSeries().getEndIndex() - 1);
        boolean resultShort = shortEntryRule.isSatisfied(priceTracker.getSeries().getEndIndex() - 1);
        LOG.info(() -> {
            Num currentLongEma = longEma.getValue(priceTracker.getSeries().getEndIndex());
            Num currentShortEma = shortEma.getValue(priceTracker.getSeries().getEndIndex());
            return market.getName() +
                    "\n######### MOVED UP? #########\n" +
                    "* Current market price: " + priceTracker.getFormattedLast() +
                    "\n* Current long EMA value: " + priceTracker.formatWithCounterCurrency((BigDecimal) currentLongEma.getDelegate()) +
                    "\n* Current short EMA value: " + priceTracker.formatWithCounterCurrency((BigDecimal) currentShortEma.getDelegate()) +
                    "\n* Place a BUY order? (long): " + resultLong +
                    "\n* Place a SELL order? (short): " + resultShort +
                    "\n#############################";
        });
        if (resultLong) {
            return Optional.of(LONG_POSITION);
        }
        return resultShort ? Optional.of(SHORT_POSITION) : Optional.empty();
    }

    @Override
    protected boolean shouldExitMarket() throws TradingApiException, ExchangeNetworkException {
        return true;
    }

    @Override
    protected Collection<? extends Ta4j2Chart.ChartIndicatorConfig> createStrategySpecificOverviewChartIndicators() throws TradingApiException, ExchangeNetworkException {
        return createStrategySpecificLiveChartIndicators();
    }

    @Override
    protected void botWillShutdown() {
    }
}
