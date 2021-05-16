package com.gazbert.bxbot.strategies.helper;

import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.TripleEMAIndicator;
import org.ta4j.core.indicators.keltner.KeltnerChannelMiddleIndicator;
import org.ta4j.core.num.Num;

public class TripleKeltnerChannelMiddleIndicator extends KeltnerChannelMiddleIndicator {
    private final TripleEMAIndicator tripleEmaIndicator;

    public TripleKeltnerChannelMiddleIndicator(Indicator<Num> indicator, int keltnerBarCount) {
        super(indicator, keltnerBarCount);
        this.tripleEmaIndicator = new TripleEMAIndicator(indicator, keltnerBarCount);
    }


    @Override
    protected Num calculate(int index) {
        return this.tripleEmaIndicator.getValue(index);
    }
}
