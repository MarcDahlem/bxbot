package com.gazbert.bxbot.trading.api.util.ta4j;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.num.Num;

public class LowestPivotPointIndicator extends MovingPivotPointIndicator {
    private final LowPriceIndicator lowPriceIndicator;
    private final Indicator<Num> valueIndicator;
    private final HighPriceIndicator highPriceIndicator;

    public LowestPivotPointIndicator(BarSeries series) {
        this(series, null);
    }

    @Override
    protected Indicator<Num> getConfirmationIndicator() {
        return highPriceIndicator;
    }

    public LowestPivotPointIndicator(BarSeries series, Indicator<Num> valueIndicator) {
        super(series);
        this.highPriceIndicator = new HighPriceIndicator(series);
        this.lowPriceIndicator = new LowPriceIndicator(series);
        this.valueIndicator = valueIndicator == null ? lowPriceIndicator : valueIndicator;
    }

    @Override
    protected Indicator<Num> getPivotIndicator() {
        return lowPriceIndicator;
    }

    @Override
    protected Indicator<Num> getValueIndicator() {
        return this.valueIndicator;
    }

    @Override
    protected boolean contradictsPivot(Num valueToCheck, Num otherValue) {
        return valueToCheck.isGreaterThan(otherValue);
    }
}
