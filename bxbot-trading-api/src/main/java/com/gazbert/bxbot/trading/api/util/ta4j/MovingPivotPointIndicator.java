package com.gazbert.bxbot.trading.api.util.ta4j;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.AbstractIndicator;
import org.ta4j.core.num.Num;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import static org.ta4j.core.num.NaN.NaN;

public abstract class MovingPivotPointIndicator extends AbstractIndicator<Num> {

    private MovingPivotPointIndicator oppositPivotIndicator;

    private final Map<Integer, TreeSet<Integer>> reversalPoints = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(final Map.Entry eldest) {
            return size() > 10;
        }
    };

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
        int currentTick = getBarSeries().getEndIndex();
        if ((!reversalPoints.containsKey(currentTick)) || index == currentTick) {
            ConfirmationMap confirmationMap = buildConfirmationMap();
            TreeSet<Integer> computedReversalPoints = confirmationMap.computeReversalPoints(this::contradictsPivot);
            reversalPoints.put(currentTick, computedReversalPoints);
        }

        TreeSet<Integer> storedReversalPoints = reversalPoints.get(currentTick);
        Integer lastReversalPoint = storedReversalPoints.floor(index);
        return lastReversalPoint == null ? Optional.empty() : Optional.of(lastReversalPoint);
    }

    private ConfirmationMap buildConfirmationMap() {
        ConfirmationMap map = new ConfirmationMap();
        Num maximaSinceLastOpposite = null;
        for (int i = getBarSeries().getBeginIndex(); i <= getBarSeries().getEndIndex(); i++) {
            if (isConfirmed(i)) {
                if (maximaSinceLastOpposite == null || contradictsPivot(maximaSinceLastOpposite, getPivotIndicator().getValue(i))) {
                    map.addConfirmation(i, getPivotIndicator().getValue(i));
                }
            }
            if (oppositPivotIndicator.isConfirmed(i)) {
                map.addOppositeConfirmation(i);
                maximaSinceLastOpposite = getPivotIndicator().getValue(i);
            } else {
                if (maximaSinceLastOpposite == null || contradictsPivot(maximaSinceLastOpposite, getPivotIndicator().getValue(i))) {
                    maximaSinceLastOpposite = getPivotIndicator().getValue(i);
                }
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
            if (oppositPivotIndicator.isConfirmed(inFrameIndex)) {
                return false;
            }
            Num confirmationValue = getConfirmationIndicator().getValue(inFrameIndex);
            if (contradictsPivot(confirmationValue, lastConfirmation)) {
                confirmations++;
                if (confirmations >= 2) {
                    return true;
                }
                lastConfirmation = confirmationValue;
            }
        }
        return false;
    }

    public void setOppositPivotIndicator(MovingPivotPointIndicator oppositPivotIndicator) {
        this.oppositPivotIndicator = oppositPivotIndicator;
    }

    protected abstract Indicator<Num> getConfirmationIndicator();

    protected abstract Indicator<Num> getPivotIndicator();

    protected abstract Indicator<Num> getValueIndicator();

    protected abstract boolean contradictsPivot(Num valueToCheck, Num otherValue);
}
