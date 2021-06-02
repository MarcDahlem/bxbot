package com.gazbert.bxbot.trading.api.util.ta4j;

import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiFunction;
import org.ta4j.core.num.Num;

public class ConfirmationMap {
    private TreeMap<Integer, Num> confirmations = new TreeMap<>();
    private TreeSet<Integer> oppositeConfirmations = new TreeSet<>();

    public void addConfirmation(int i, Num value) {
        confirmations.put(i, value);
    }

    public void addOppositeConfirmation(int i) {
        oppositeConfirmations.add(i);
    }

    public TreeSet<Integer> computeReversalPoints(BiFunction<Num, Num, Boolean> contradictsPivot) {
        TreeSet<Integer> reversalPoints = new TreeSet<>();

        Map.Entry<Integer, Num> lastConfirmation = confirmations.firstEntry();
        Map.Entry<Integer, Num> nextConfirmation = null;
        if (lastConfirmation != null) {
            nextConfirmation = confirmations.higherEntry(lastConfirmation.getKey());
        }

        while(lastConfirmation != null) {
            Integer nextOppositeConfirmIndex = oppositeConfirmations.higher(lastConfirmation.getKey());
            if (nextConfirmation == null) {
                reversalPoints.add(lastConfirmation.getKey());
                lastConfirmation = nextConfirmation;
            } else {

                if (nextOppositeConfirmIndex == null) {
                    if (contradictsPivot.apply(lastConfirmation.getValue(), nextConfirmation.getValue())) {
                        lastConfirmation = nextConfirmation;
                    }
                    nextConfirmation = confirmations.higherEntry(nextConfirmation.getKey());

                } else {
                    if (nextOppositeConfirmIndex<nextConfirmation.getKey()) {
                        reversalPoints.add(lastConfirmation.getKey());
                        lastConfirmation = nextConfirmation;
                        nextConfirmation = confirmations.higherEntry(nextConfirmation.getKey());
                    } else {
                        if (contradictsPivot.apply(lastConfirmation.getValue(), nextConfirmation.getValue())) {
                            lastConfirmation = nextConfirmation;
                        }
                        nextConfirmation = confirmations.higherEntry(nextConfirmation.getKey());
                    }
                }
            }
        }
        return reversalPoints;
    }
}
