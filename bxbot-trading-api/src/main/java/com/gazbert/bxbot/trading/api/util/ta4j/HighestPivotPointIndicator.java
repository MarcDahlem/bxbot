package com.gazbert.bxbot.trading.api.util.ta4j;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.num.Num;

public class HighestPivotPointIndicator extends MovingPivotPointIndicator {
    private final HighPriceIndicator highPriceIndicator;

    public HighestPivotPointIndicator(BarSeries series, int frameSize) {
        super(series, frameSize);
        this.highPriceIndicator = new HighPriceIndicator(series);
    }

    @Override
    protected Indicator<Num> getIndicator() {
        return highPriceIndicator;
    }

    @Override
    protected boolean contradictsPivot(Num valueToCheck, Num otherValue) {
        return valueToCheck.isLessThan(otherValue);
    }
}
