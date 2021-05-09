package com.gazbert.bxbot.trading.api.util.ta4j;

import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.FixedRule;

import java.awt.*;
import java.util.Collection;
import java.util.LinkedList;

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
        Indicator<Num> ask = new HighPriceIndicator(breakEvenIndicator.getBarSeries());
        Indicator<Num> bid = new LowPriceIndicator(breakEvenIndicator.getBarSeries());
        Indicator<Num> close = new ClosePriceIndicator(breakEvenIndicator.getBarSeries());

        Collection<Ta4j2Chart.ChartIndicatorConfig> indicatorConfigs = new LinkedList<>();
        indicatorConfigs.add(new Ta4j2Chart.ChartIndicatorConfig(breakEvenIndicator, "break even", Ta4j2Chart.BREAK_EVEN_COLOR));
        indicatorConfigs.add(new Ta4j2Chart.ChartIndicatorConfig(ask, "ask", Ta4j2Chart.ASK_PRICE_COLOR));
        indicatorConfigs.add(new Ta4j2Chart.ChartIndicatorConfig(close, "close", Ta4j2Chart.CLOSE_PRICE_COLOR));
        indicatorConfigs.add(new Ta4j2Chart.ChartIndicatorConfig(bid, "bid", Ta4j2Chart.BID_PRICE_COLOR));

        return indicatorConfigs;
    }
}
