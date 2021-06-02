package com.gazbert.bxbot.trading.api.util.ta4j;

import static org.ta4j.core.num.NaN.NaN;

import java.util.Optional;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.AbstractIndicator;
import org.ta4j.core.num.Num;

public abstract class MovingPivotPointIndicator extends AbstractIndicator<Num> {

    private MovingPivotPointIndicator oppositPivotIndicator;

    protected MovingPivotPointIndicator(BarSeries series) {
        super(series);
    }

    @Override
    public Num getValue(int index) {
        Optional<Integer> latestPivotIndex = getLatestPivotIndex(index);
        if (latestPivotIndex.isPresent()) {
            return this.getValueIndicator().getValue(latestPivotIndex.get());
        }
        return NaN;
    }

    private Optional<Integer> getLatestPivotIndex(int index) {
        Integer lastConfirmation = null;
        Integer lastOppositeConfirmation = null;
        while (index >= getBarSeries().getBeginIndex()) {
            if (isConfirmed(index)) {
                if (lastConfirmation==null) {
                    lastConfirmation = index;
                } else {
                    Num lastConfirmationValue = getPivotIndicator().getValue(lastConfirmation);
                    Num currentConfirmationValue = getPivotIndicator().getValue(index);
                    if (!contradictsPivot(currentConfirmationValue, lastConfirmationValue)) {
                        lastConfirmation = index;
                    }
                }
            }
            if (oppositPivotIndicator.isConfirmed(index)) {
                if(lastConfirmation == null) {
                    return getLatestPivotIndex(index-1);
                } else {
                    return Optional.of(lastConfirmation);
                }
            }
            index--;
        }
        if(lastConfirmation == null) {
            return Optional.empty();
        } else {
            return Optional.of(lastConfirmation);
        }
    }

    private boolean isConfirmed(int index) {
        Num valueToCheck = getPivotIndicator().getValue(index);
        int endIndex = getBarSeries().getEndIndex();
        Num lastConfirmation = getConfirmationIndicator().getValue(index);

        int confirmations = 0;
        for (int inFrameIndex = index + 1; inFrameIndex <= endIndex; inFrameIndex++) {
            Num otherValue = getPivotIndicator().getValue(inFrameIndex);
            if (contradictsPivot(valueToCheck, otherValue)) {
                return false;
            }
            Num confirmationValue = getConfirmationIndicator().getValue(inFrameIndex);
            if (contradictsPivot(confirmationValue, lastConfirmation)) {
                confirmations++;
                lastConfirmation = confirmationValue;
            }
            if (oppositPivotIndicator.isConfirmed(inFrameIndex)) {
                return confirmations>=2;
            }
        }
        return confirmations>=2;
    }

    private boolean confirmsInThePast(int index) {
        return true;
        /*int startIndex = getBarSeries().getBeginIndex();
        for (int inFrameIndex = index-1; inFrameIndex >= startIndex; inFrameIndex--) {
            if (isPivotIndex(inFrameIndex)) {
                return false;
            }
            if(oppositPivotIndicator.isPivotIndex(inFrameIndex)) {
                return true;
            }
        }
        return true;*/
    }

    public void setOppositPivotIndicator(MovingPivotPointIndicator oppositPivotIndicator) {
        this.oppositPivotIndicator = oppositPivotIndicator;
    }

    protected abstract Indicator<Num> getConfirmationIndicator();

    protected abstract Indicator<Num> getPivotIndicator();

    protected abstract Indicator<Num> getValueIndicator();

    protected abstract boolean contradictsPivot(Num valueToCheck, Num otherValue);
}
