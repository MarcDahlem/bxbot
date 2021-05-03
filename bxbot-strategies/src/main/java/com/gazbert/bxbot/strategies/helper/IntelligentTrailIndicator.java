package com.gazbert.bxbot.strategies.helper;

import com.gazbert.bxbot.trading.api.util.ta4j.SellIndicator;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.helpers.BooleanTransformIndicator;
import org.ta4j.core.num.Num;

public class IntelligentTrailIndicator extends CachedIndicator<Num> {
    private final SellIndicator aboveBreakEvenIndicator;
    private final Indicator<Num> minAboveBreakEvenIndicator;
    private final Indicator<Num> breakEvenIndicator;
    private final SellIndicator belowBreakEvenIndicator;

    public IntelligentTrailIndicator(SellIndicator belowBreakEvenIndicator, SellIndicator aboveBreakEvenIndicator, Indicator<Num> minAboveBreakEvenIndicator, Indicator<Num> breakEvenIndicator) {
        super(belowBreakEvenIndicator);

        this.belowBreakEvenIndicator = belowBreakEvenIndicator;
        this.aboveBreakEvenIndicator = aboveBreakEvenIndicator;
        this.minAboveBreakEvenIndicator = minAboveBreakEvenIndicator;
        this.breakEvenIndicator = breakEvenIndicator;
    }


    @Override
    protected Num calculate(int i) {
        Num breakEven = breakEvenIndicator.getValue(i);
        if(minAboveBreakEvenIndicator.getValue(i).isGreaterThanOrEqual(breakEven)) {
            return minAboveBreakEvenIndicator.getValue(i).max(aboveBreakEvenIndicator.getValue(i));
        }
        return belowBreakEvenIndicator.getValue(i);
    }
}
