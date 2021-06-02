package com.gazbert.bxbot.trading.api.util.ta4j;

import static org.ta4j.core.num.NaN.NaN;

import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
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
        ConfirmationMap confirmationMap = buildConfirmationMap();
        TreeSet<Integer> reversalPoints = confirmationMap.computeReversalPoints(this::contradictsPivot);
        Integer lastReversalPoint = reversalPoints.floor(index);
        return lastReversalPoint==null ? Optional.empty() : Optional.of(lastReversalPoint);
    }

    private ConfirmationMap buildConfirmationMap() {
        ConfirmationMap map = new ConfirmationMap();
        for(int i = getBarSeries().getBeginIndex(); i<= getBarSeries().getEndIndex(); i++) {
            if (isConfirmed(i)) {
                map.addConfirmation(i, getConfirmationIndicator().getValue(i));
            }
            if (oppositPivotIndicator.isConfirmed(i)) {
                map.addOppositeConfirmation(i);
            }
        }
        return map;
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
                if (confirmations>=3) {
                    return true;
                }
                lastConfirmation = confirmationValue;
            }
        }
        return false;
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
