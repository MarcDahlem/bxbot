package com.gazbert.bxbot.trading.api.util.ta4j;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.ichimoku.IchimokuLineIndicator;
import org.ta4j.core.num.Num;

public class IchimokuLaggingSpanIndicator extends CachedIndicator<Num> {


    private final Indicator<Num> currentPriceIndicator;

    public IchimokuLaggingSpanIndicator(Indicator<Num> currentPriceIndicator) {
        super(currentPriceIndicator);
        this.currentPriceIndicator = currentPriceIndicator;
    }


    @Override
    protected Num calculate(int i) {
        return currentPriceIndicator.getValue(i);
    }
}
