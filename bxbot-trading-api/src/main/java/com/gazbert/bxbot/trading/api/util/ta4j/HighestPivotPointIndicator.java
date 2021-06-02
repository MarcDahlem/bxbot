package com.gazbert.bxbot.trading.api.util.ta4j;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.num.Num;

public class HighestPivotPointIndicator extends MovingPivotPointIndicator {
    private final HighPriceIndicator highPriceIndicator;
    private final Indicator<Num> valueIndicator;
    private final LowPriceIndicator lowPriceIndicator;

    public HighestPivotPointIndicator(BarSeries series) {
        this(series, null);
    }

    public HighestPivotPointIndicator(BarSeries series, Indicator<Num> valueIndicator) {
        super(series);
        this.highPriceIndicator = new HighPriceIndicator(series);
        this.lowPriceIndicator = new LowPriceIndicator(series);
        this.valueIndicator = valueIndicator == null ? highPriceIndicator : valueIndicator;
    }

    @Override
    protected Indicator<Num> getPivotIndicator() {
        return highPriceIndicator;
    }

    @Override
    protected Indicator<Num> getValueIndicator() {
        return valueIndicator;
    }

    @Override
    protected boolean contradictsPivot(Num valueToCheck, Num otherValue) {
        return valueToCheck.isLessThan(otherValue);
    }

    @Override
    protected Indicator<Num> getConfirmationIndicator() {
        return lowPriceIndicator;
    }
}
