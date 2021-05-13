package com.gazbert.bxbot.trading.api.util.ta4j;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.helpers.TransformIndicator;
import org.ta4j.core.indicators.ichimoku.IchimokuLineIndicator;
import org.ta4j.core.num.Num;

public class IchimokuLead2FutureIndicator extends CachedIndicator<Num> {

    private final IchimokuLineIndicator lead2Future;

    public IchimokuLead2FutureIndicator(BarSeries series, int barCount) {
        super(series);
        lead2Future = new IchimokuLineIndicator(series, barCount);
    }


    @Override
    protected Num calculate(int i) {
        return lead2Future.getValue(i);
    }
}
