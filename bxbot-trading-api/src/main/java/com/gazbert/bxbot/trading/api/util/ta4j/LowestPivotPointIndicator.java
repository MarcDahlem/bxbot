package com.gazbert.bxbot.trading.api.util.ta4j;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.num.Num;

public class LowestPivotPointIndicator extends MovingPivotPointIndicator {
    private final LowPriceIndicator lowPriceIndicator;
    private final Indicator<Num> valueIndicator;

    public LowestPivotPointIndicator(BarSeries series, int frameSize) {
        this(series, null, frameSize);
    }

    public LowestPivotPointIndicator(BarSeries series, Indicator<Num> valueIndicator, int frameSize) {
        super(series, frameSize);
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
