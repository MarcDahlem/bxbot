package com.gazbert.bxbot.trading.api.util.ta4j;

import java.util.Collection;
import java.util.LinkedList;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.rules.FixedRule;

public class RecordedStrategy extends BaseStrategy {
    private final SellIndicator breakEvenIndicator;

    protected RecordedStrategy(String name, SellIndicator breakEvenIndicator) {
        super(name, new FixedRule(breakEvenIndicator.getRecordedBuyOrderExecutions()), new FixedRule(breakEvenIndicator.getRecordedSellOrderExecutions()));
        this.breakEvenIndicator = breakEvenIndicator;
    }

    public static RecordedStrategy createStrategyFromRecording(String strategyName, SellIndicator beIndicator) {
        return new RecordedStrategy(strategyName, beIndicator);
    }

    public Collection<Ta4j2Chart.ChartIndicatorConfig> createChartIndicators() {
        Collection<Ta4j2Chart.ChartIndicatorConfig> indicatorConfigs = new LinkedList<>();
        indicatorConfigs.add(new Ta4j2Chart.ChartIndicatorConfig(breakEvenIndicator, "break even", Ta4j2Chart.BREAK_EVEN_COLOR));
        return indicatorConfigs;
    }
}
