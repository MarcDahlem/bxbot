package com.gazbert.bxbot.trading.api.util.ta4j;

import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.CachedIndicator;

import org.ta4j.core.indicators.helpers.TransformIndicator;
import org.ta4j.core.num.Num;


import static org.ta4j.core.indicators.helpers.TransformIndicator.divide;
import static com.gazbert.bxbot.trading.api.util.ta4j.CombineIndicator.plus;

public class IchimokuLead1FutureIndicator extends CachedIndicator<Num> {

    private final TransformIndicator lead1Future;

    public IchimokuLead1FutureIndicator(Indicator<Num> conversionLine, Indicator<Num> baseLine) {
        super(conversionLine);
        lead1Future = divide(plus(conversionLine, baseLine), 2);
    }


    @Override
    protected Num calculate(int i) {
        return lead1Future.getValue(i);
    }
}
