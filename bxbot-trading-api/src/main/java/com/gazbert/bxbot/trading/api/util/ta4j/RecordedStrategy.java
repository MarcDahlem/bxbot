package com.gazbert.bxbot.trading.api.util.ta4j;

import com.google.common.primitives.Ints;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Indicator;
import org.ta4j.core.Rule;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.FixedRule;

import java.awt.*;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;

public class RecordedStrategy extends BaseStrategy {
    private final BreakEvenIndicator breakEvenIndicator;
    public static final Color CLOSE_PRICE_COLOR = new Color(183, 28, 28);
    public static  final Color BID_PRICE_COLOR = new Color(255, 111, 0);
    public static  final Color ASK_PRICE_COLOR = new Color(130, 119, 23);
    public static final Color BREAK_EVEN_COLOR = new Color(0, 200, 83);

    protected RecordedStrategy(String name, BreakEvenIndicator breakEvenIndicator) {
        super(name, new FixedRule(breakEvenIndicator.getRecordedBuyOrderExecutions()), new FixedRule(breakEvenIndicator.getRecordedSellOrderExecutions()));
        this.breakEvenIndicator = breakEvenIndicator;
    }

    public static RecordedStrategy createStrategyFromRecording(String strategyName, BreakEvenIndicator beIndicator) {
        return new RecordedStrategy(strategyName, beIndicator);
    }

    public Collection<Ta4j2Chart.ChartIndicatorConfig> createChartIndicators() {
        Indicator<Num> ask = new HighPriceIndicator(breakEvenIndicator.getBarSeries());
        Indicator<Num> bid = new LowPriceIndicator(breakEvenIndicator.getBarSeries());
        Indicator<Num> close = new ClosePriceIndicator(breakEvenIndicator.getBarSeries());

        Collection<Ta4j2Chart.ChartIndicatorConfig> indicatorConfigs = new HashSet<>();
        indicatorConfigs.add(new Ta4j2Chart.ChartIndicatorConfig(breakEvenIndicator, "break even", BREAK_EVEN_COLOR));
        indicatorConfigs.add(new Ta4j2Chart.ChartIndicatorConfig(ask, "ask", ASK_PRICE_COLOR));
        indicatorConfigs.add(new Ta4j2Chart.ChartIndicatorConfig(close, "close", CLOSE_PRICE_COLOR));
        indicatorConfigs.add(new Ta4j2Chart.ChartIndicatorConfig(bid, "bid", BID_PRICE_COLOR));

        return indicatorConfigs;
    }
}
