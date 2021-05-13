package com.gazbert.bxbot.trading.api.util.ta4j;

import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.num.Num;
import static org.ta4j.core.num.NaN.NaN;

public class DelayIndicator extends CachedIndicator<Num> {
    private final int delay;
    private final Indicator<Num> indicator;

    public DelayIndicator(Indicator<Num> indicator, Integer delay) {
        super(indicator);
        this.indicator = indicator;
        this.delay = delay;
    }

    @Override
    protected Num calculate(int index) {
        int indexInPast = index - delay;
        if (indexInPast >= getBarSeries().getBeginIndex()) {
            return indicator.getValue(indexInPast);
        }
        return NaN;
    }
}
