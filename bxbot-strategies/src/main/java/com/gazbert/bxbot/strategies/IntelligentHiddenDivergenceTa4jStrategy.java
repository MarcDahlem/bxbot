package com.gazbert.bxbot.strategies;

import static com.gazbert.bxbot.trading.api.util.ta4j.CombineIndicator.minus;
import static com.gazbert.bxbot.trading.api.util.ta4j.CombineIndicator.plus;
import static com.gazbert.bxbot.trading.api.util.ta4j.MarketEnterType.LONG_POSITION;
import static com.gazbert.bxbot.trading.api.util.ta4j.MarketEnterType.SHORT_POSITION;
import static org.ta4j.core.indicators.helpers.TransformIndicator.multiply;

import com.gazbert.bxbot.strategies.helper.IntelligentEnterPriceCalculator;
import com.gazbert.bxbot.strategies.helper.IntelligentStateTracker;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.TradingApiException;
import com.gazbert.bxbot.trading.api.util.ta4j.DelayIndicator;
import com.gazbert.bxbot.trading.api.util.ta4j.HighestPivotPointIndicator;
import com.gazbert.bxbot.trading.api.util.ta4j.LowestPivotPointIndicator;
import com.gazbert.bxbot.trading.api.util.ta4j.MarketEnterType;
import com.gazbert.bxbot.trading.api.util.ta4j.Ta4j2Chart;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Optional;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Indicator;
import org.ta4j.core.Rule;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.ChandelierExitLongIndicator;
import org.ta4j.core.indicators.ChandelierExitShortIndicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.ConstantIndicator;
import org.ta4j.core.indicators.helpers.TransformIndicator;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.BooleanRule;
import org.ta4j.core.rules.OverIndicatorRule;
import org.ta4j.core.rules.UnderIndicatorRule;

@Component("intelligentHiddenDivergenceTa4jStrategy")
// used to load the strategy using Spring bean injection
@Scope("prototype") // create always a new instance if it is injected
public class IntelligentHiddenDivergenceTa4jStrategy extends AbstractIntelligentStrategy {

    private BaseStrategy ta4jStrategyLong;
    private BaseStrategy ta4jStrategyShort;

    private Indicator<Num> longEma;
    private Indicator<Num> shortEma;
    private Indicator<Num> rsi;
    private Indicator<Num> highPivotPoints;
    private Indicator<Num> lowPivotPoints;
    private Indicator<Num> emaUpTrendLine;
    private Indicator<Num> emaDownTrendLine;
    private Indicator<Num> rsiAtHighPivotPoints;
    private Indicator<Num> rsiAtLowPivotPoints;
    private Indicator<Num> chandelierExitLongIndicator;
    private Indicator<Num> chandelierExitShortIndicator;

    @Override
    protected void botWillStartup(StrategyConfig config) throws TradingApiException, ExchangeNetworkException {
        initTa4jStrategy();
    }

    private void initTa4jStrategy() throws TradingApiException, ExchangeNetworkException {
        BarSeries series = priceTracker.getSeries();
        ClosePriceIndicator closePriceIndicator = new ClosePriceIndicator(series);
        int i = 200;
        int j = 50;

        int pivotCalculationFrame = 5;
        int chandelierExitMultiplier = 6;

        longEma = new EMAIndicator(closePriceIndicator, i);
        shortEma = new EMAIndicator(closePriceIndicator, j);
        rsi = new RSIIndicator(new ClosePriceIndicator(priceTracker.getSeries()), 14);
        highPivotPoints = new HighestPivotPointIndicator(priceTracker.getSeries(), pivotCalculationFrame);
        lowPivotPoints = new LowestPivotPointIndicator(priceTracker.getSeries(), pivotCalculationFrame);
        ATRIndicator trueRangeIndicator = new ATRIndicator(priceTracker.getSeries(), 14);
        TransformIndicator trueRangeFactor = multiply(trueRangeIndicator, 2);
        emaUpTrendLine = plus(longEma, trueRangeFactor);
        emaDownTrendLine = minus(longEma, trueRangeFactor);

        rsiAtHighPivotPoints = new HighestPivotPointIndicator(priceTracker.getSeries(), rsi, pivotCalculationFrame);
        rsiAtLowPivotPoints = new LowestPivotPointIndicator(priceTracker.getSeries(), rsi, pivotCalculationFrame);

        Rule upTrend = new OverIndicatorRule(shortEma, emaUpTrendLine);
        Rule priceOverLongReversalArea = new OverIndicatorRule(closePriceIndicator, emaUpTrendLine);

        DelayIndicator previousLowPivotPoint = new DelayIndicator(lowPivotPoints, 1);
        DelayIndicator previousRsiAtLowPivotPoint = new DelayIndicator(rsiAtLowPivotPoints, 1);

        Rule lowPriceMovesUp = new OverIndicatorRule(lowPivotPoints, previousLowPivotPoint);
        Rule oversoldIndicatorMovesDown = new UnderIndicatorRule(rsiAtLowPivotPoints, previousRsiAtLowPivotPoint);

        LowestPivotPointIndicator secondLastLowestPivotPoint = new LowestPivotPointIndicator(priceTracker.getSeries(), previousLowPivotPoint, pivotCalculationFrame);
        LowestPivotPointIndicator secondLastRsiAtLowPivotPoints = new LowestPivotPointIndicator(priceTracker.getSeries(), previousRsiAtLowPivotPoint, pivotCalculationFrame);

        Rule lowPriceMovesUpInTwo = new OverIndicatorRule(lowPivotPoints, secondLastLowestPivotPoint);
        Rule oversoldIndicatorMovesDownInTwo = new UnderIndicatorRule(rsiAtLowPivotPoints, secondLastRsiAtLowPivotPoints);

        Rule divergenceWithLastPivot = lowPriceMovesUp.and(oversoldIndicatorMovesDown);
        Rule divergenceWithSecondLastPivot = lowPriceMovesUpInTwo.and(oversoldIndicatorMovesDownInTwo);

        Rule longDivergence = divergenceWithLastPivot.or(divergenceWithSecondLastPivot)

        Rule longEntryRule = upTrend.and(priceOverLongReversalArea).and(longDivergence);

        chandelierExitLongIndicator = new ChandelierExitLongIndicator(priceTracker.getSeries(), 22, chandelierExitMultiplier);

        Rule longExitRule = new UnderIndicatorRule(closePriceIndicator,chandelierExitLongIndicator);

        Rule downTrend = new UnderIndicatorRule(shortEma, emaDownTrendLine);
        Rule priceUnderLongReversalArea = new UnderIndicatorRule(closePriceIndicator, emaDownTrendLine);
        Rule highPriceMovesDown = new UnderIndicatorRule(highPivotPoints, new DelayIndicator(highPivotPoints, 1));
        Rule oversoldIndicatorMovesUp = new OverIndicatorRule(rsiAtHighPivotPoints, new DelayIndicator(rsiAtHighPivotPoints, 1));

        Rule shortEntryRule = downTrend.and(priceUnderLongReversalArea).and(highPriceMovesDown).and(oversoldIndicatorMovesUp);

        chandelierExitShortIndicator = new ChandelierExitShortIndicator(priceTracker.getSeries(), 22, chandelierExitMultiplier);

        Rule shortExitRule = new OverIndicatorRule(closePriceIndicator,chandelierExitShortIndicator);

        ta4jStrategyLong = new BaseStrategy("Intelligent Ta4j Hidden Divergence (long)", longEntryRule, longExitRule);
        ta4jStrategyShort = new BaseStrategy("Intelligent Ta4j Hidden Divergence (short)", shortEntryRule, shortExitRule);
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
        result.add(new Ta4j2Chart.ChartIndicatorConfig(highPivotPoints, "last high", Ta4j2Chart.ASK_PRICE_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(rsiAtHighPivotPoints, "rsi at last high", Ta4j2Chart.SELL_LIMIT_1_COLOR, rsiYAxisConfig));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(lowPivotPoints, "last low", Ta4j2Chart.BID_PRICE_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(rsiAtLowPivotPoints, "rsi at last low", Ta4j2Chart.SELL_CURRENT_LIMIT_COLOR, rsiYAxisConfig));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(emaUpTrendLine, "+ATR14", Ta4j2Chart.BUY_TRIGGER_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(emaDownTrendLine, "-ATR14", Ta4j2Chart.SELL_LIMIT_3_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(chandelierExitLongIndicator, "ChandelierExit (long)", Ta4j2Chart.SELL_LIMIT_1_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(chandelierExitShortIndicator, "ChandelierExit (short)", Ta4j2Chart.SELL_LIMIT_2_COLOR));

        return result;
    }

    @Override
    protected IntelligentStateTracker.OrderPriceCalculator createExitPriceCalculator(StrategyConfig config) throws TradingApiException, ExchangeNetworkException {
        return new IntelligentStateTracker.OrderPriceCalculator() {
            @Override
            public BigDecimal calculate(MarketEnterType marketEnterType) throws TradingApiException, ExchangeNetworkException, StrategyException {
                return priceTracker.getLast();
            }

            @Override
            public void logStatistics(MarketEnterType marketEnterType) throws TradingApiException, ExchangeNetworkException, StrategyException {

            }
        };
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
        boolean resultLong = ta4jStrategyLong.shouldEnter(priceTracker.getSeries().getEndIndex() - 1);
        boolean resultShort = ta4jStrategyShort.shouldEnter(priceTracker.getSeries().getEndIndex() - 1);
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
        int currentIndex = priceTracker.getSeries().getEndIndex();
        int lastEntryIndex = stateTracker.getBreakEvenIndicator().getLastRecordedEntryIndex();
        int checkIndex = lastEntryIndex == currentIndex ? currentIndex : currentIndex - 1;
        boolean resultLong = ta4jStrategyLong.shouldExit(checkIndex);
        boolean resultShort = ta4jStrategyLong.shouldExit(checkIndex);

        switch (stateTracker.getCurrentMarketEntry()) {
            case SHORT_POSITION:
                return resultShort;
            case LONG_POSITION:
                return resultLong;
            default:
                throw new IllegalStateException("Unkown entry type encountered: " + stateTracker.getCurrentMarketEntry());
        }
    }

    @Override
    protected Collection<? extends Ta4j2Chart.ChartIndicatorConfig> createStrategySpecificOverviewChartIndicators() throws TradingApiException, ExchangeNetworkException {
        return createStrategySpecificLiveChartIndicators();
    }

    @Override
    protected void botWillShutdown() {
    }
}
