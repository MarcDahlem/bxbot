package com.gazbert.bxbot.trading.api.util.ta4j;

import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.AbstractIndicator;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.helpers.PreviousValueIndicator;
import org.ta4j.core.num.Num;
import static org.ta4j.core.num.NaN.NaN;

public class DelayIndicator extends AbstractIndicator<Num> {
    private final int delay;
    private final Indicator<Num> indicator;

    public DelayIndicator(Indicator<Num> indicator, int delay) {
        super(indicator.getBarSeries());
        this.indicator = indicator;
        this.delay = delay;
    }

    @Override
    public Num getValue(int index) {
        int indexInPast = index - delay;
        if (indexInPast >= getBarSeries().getBeginIndex()) {
            return indicator.getValue(indexInPast);
        }
        return NaN;
    }
}
