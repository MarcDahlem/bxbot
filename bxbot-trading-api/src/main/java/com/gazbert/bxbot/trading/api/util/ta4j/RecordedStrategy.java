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

    protected RecordedStrategy(String name, Rule entryRule, Rule exitRule, BreakEvenIndicator breakEvenIndicator) {
        super(name, entryRule, exitRule);
        this.breakEvenIndicator = breakEvenIndicator;
    }

    public static RecordedStrategy createStrategyFromRecording(String strategyName, BarSeries series, BigDecimal buyFee, BigDecimal sellFee, Collection<Integer> buyIndeces, Collection<Integer> sellIndeces) {
        BreakEvenIndicator beIndicator = new BreakEvenIndicator(new HighPriceIndicator(series), buyFee, sellFee, buyIndeces, sellIndeces);
        return new RecordedStrategy(strategyName, new FixedRule(Ints.toArray(buyIndeces)), new FixedRule(Ints.toArray(sellIndeces)), beIndicator);
    }

    public Collection<Ta4j2Chart.ChartIndicatorConfig> createChartIndicators() {
        Indicator<Num> ask = new HighPriceIndicator(breakEvenIndicator.getBarSeries());
        Indicator<Num> bid = new LowPriceIndicator(breakEvenIndicator.getBarSeries());
        Indicator<Num> close = new ClosePriceIndicator(breakEvenIndicator.getBarSeries());

        Collection<Ta4j2Chart.ChartIndicatorConfig> indicatorConfigs = new HashSet<>();
        indicatorConfigs.add(new Ta4j2Chart.ChartIndicatorConfig(breakEvenIndicator, "break even", new Color(0,200,83)));
        indicatorConfigs.add(new Ta4j2Chart.ChartIndicatorConfig(ask, "ask", ASK_PRICE_COLOR));
        indicatorConfigs.add(new Ta4j2Chart.ChartIndicatorConfig(close, "close", CLOSE_PRICE_COLOR));
        indicatorConfigs.add(new Ta4j2Chart.ChartIndicatorConfig(bid, "bid", BID_PRICE_COLOR));

        return indicatorConfigs;
    }
}
