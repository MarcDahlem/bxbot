package com.gazbert.bxbot.trading.api.util.ta4j;

import java.util.Collection;
import java.util.LinkedList;
import java.util.NavigableSet;
import java.util.TreeSet;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.num.Num;

public class ReversalComputationState {


    private final HighPriceIndicator highPriceIndicator;
    private final LowPriceIndicator lowPriceIndicator;

    private final LinkedList<Num> lowestSinceLastConfirmedHigh = new LinkedList<>();
    private final LinkedList<Num> highestSinceLastConfirmedLow = new LinkedList<>();


    private final LinkedList<Integer> possibleHighs = new LinkedList<>();
    private final LinkedList<Integer> possibleLows = new LinkedList<>();
    private final LinkedList<Integer> highs = new LinkedList<>();
    private final LinkedList<Integer> lows = new LinkedList<>();
    private NavigableSet<Num> lowsSinceLastLow = new TreeSet<>();
    private NavigableSet<Num> highsSinceLastHigh = new TreeSet<>();

    public ReversalComputationState(HighPriceIndicator highPriceIndicator, LowPriceIndicator lowPriceIndicator) {

        this.highPriceIndicator = highPriceIndicator;
        this.lowPriceIndicator = lowPriceIndicator;
    }

    public void update(int index) {
        Num currentHighPrice = highPriceIndicator.getValue(index);
        Num currentLowPrice = lowPriceIndicator.getValue(index);

        boolean highAdded = false;
        boolean lowAdded = false;

        if(lowestSinceLastConfirmedHigh.isEmpty()) {
            lowestSinceLastConfirmedHigh.add(currentLowPrice);
        } else {
            if(currentLowPrice.isLessThan(lowestSinceLastConfirmedHigh.getLast())) {
                lowestSinceLastConfirmedHigh.add(currentLowPrice);
                if(doesCurrentLowPriceConfirmsLow(currentHighPrice)) {
                    possibleLows.add(index);
                    highAdded = addHigh();
                }
            }
        }
        if(highestSinceLastConfirmedLow.isEmpty()) {
            highestSinceLastConfirmedLow.add(currentHighPrice);
        } else {
            if (currentHighPrice.isGreaterThan(highestSinceLastConfirmedLow.getLast())) {
                highestSinceLastConfirmedLow.add(currentHighPrice);
                if(doesCurrentLowPriceConfirmsHigh(currentLowPrice)) {
                    possibleHighs.add(index);
                    lowAdded = addLow();
                }
            }
        }
        if (!lowAdded) {
            lowsSinceLastLow.add(currentLowPrice);
            lowsSinceLastLow = new TreeSet<>(lowsSinceLastLow.headSet(currentLowPrice, true));
        }

        if (!highAdded) {
            highsSinceLastHigh.add(currentHighPrice);
            highsSinceLastHigh = new TreeSet<>(highsSinceLastHigh.tailSet(currentHighPrice, true));
        }
    }

    private boolean addHigh() {
        if (!possibleHighs.isEmpty()) {
            Num lastHigh = highPriceIndicator.getValue(possibleHighs.getLast());
            if (lastHigh.equals(highestSinceLastConfirmedLow.getLast())) {
                highs.add(possibleHighs.getLast());
                possibleHighs.clear();
                lowestSinceLastConfirmedHigh.clear();
                highsSinceLastHigh.clear();
                return true;
            }
        }
        return false;
    }

    private boolean addLow() {
        if (!possibleLows.isEmpty()) {
            Num lastLowPrice = lowPriceIndicator.getValue(possibleLows.getLast());
            if (lastLowPrice.equals(lowestSinceLastConfirmedHigh.getLast())) {
                lows.add(possibleLows.getLast());
                possibleLows.clear();
                highestSinceLastConfirmedLow.clear();
                lowsSinceLastLow.clear();
                return true;
            }
        }
        return false;
    }

    private boolean doesCurrentLowPriceConfirmsLow(Num currentHighPrice) {
        return highsSinceLastHigh.tailSet(currentHighPrice, false).size() >=2;
    }

    private boolean doesCurrentLowPriceConfirmsHigh(Num currentLowPrice) {
        return lowsSinceLastLow.headSet(currentLowPrice, false).size() >=2;
    }

    public Collection<Integer> getLows() {
        return lows;
    }

    public Collection<Integer> getHighs() {
        return highs;
    }

    public void finish() {
        addLow();
        addHigh();
    }
}
