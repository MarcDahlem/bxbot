package com.gazbert.bxbot.trading.api.util.ta4j;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.num.Num;

public class LowestPivotPointIndicator extends MovingPivotPointIndicator {
    private final LowPriceIndicator lowPriceIndicator;

    public LowestPivotPointIndicator(BarSeries series, int frameSize) {
        super(series, frameSize);
        this.lowPriceIndicator = new LowPriceIndicator(series);
    }

    @Override
    protected Indicator<Num> getIndicator() {
        return lowPriceIndicator;
    }

    @Override
    protected boolean contradictsPivot(Num valueToCheck, Num otherValue) {
        return valueToCheck.isGreaterThan(otherValue);
    }
}
