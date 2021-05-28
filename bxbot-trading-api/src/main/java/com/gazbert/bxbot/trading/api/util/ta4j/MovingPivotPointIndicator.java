package com.gazbert.bxbot.trading.api.util.ta4j;

import static org.ta4j.core.num.NaN.NaN;

import java.util.Optional;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.num.Num;

public abstract class MovingPivotPointIndicator extends CachedIndicator<Num> {

    private final int frameSize;

    protected MovingPivotPointIndicator(BarSeries series, int frameSize) {
        super(series);
        this.frameSize = frameSize;
    }

    @Override
    protected Num calculate(int index) {
        Optional<Integer> latestPivotIndex = getLatestPivotIndex(index);
        if (latestPivotIndex.isPresent()) {
            return this.getIndicator().getValue(latestPivotIndex.get());
        }
        return NaN;
    }

    protected abstract Indicator<Num> getIndicator();

    private Optional<Integer> getLatestPivotIndex(int index) {
        while (index >= getBarSeries().getBeginIndex()) {
            if (isPivotIndex(index)) {
                return Optional.of(index);
            }
            index--;
        }
        return Optional.empty();
    }

    private boolean isPivotIndex(int index) {
        Num valueToCheck = getIndicator().getValue(index);
        int startIndex = Math.max(index - frameSize, getBarSeries().getBeginIndex());
        int endIndex = Math.min(index + frameSize, getBarSeries().getEndIndex());

        for (int inFrameIndex = startIndex; inFrameIndex <= endIndex; inFrameIndex++) {
            if (index != inFrameIndex) {
                Num otherValue = getIndicator().getValue(inFrameIndex);
                if (contradictsPivot(valueToCheck, otherValue)) {
                    return false;
                }
            }
        }
        return true;
    }

    protected abstract boolean contradictsPivot(Num valueToCheck, Num otherValue);
}
