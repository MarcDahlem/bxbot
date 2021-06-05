package com.gazbert.bxbot.trading.api.util.ta4j;

import java.util.Collection;
import java.util.LinkedList;
import java.util.TreeSet;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.num.Num;

public class ReversalComputationState {


    public static final int CONFIRMATIONS = 2;
    private final HighPriceIndicator highPriceIndicator;
    private final LowPriceIndicator lowPriceIndicator;

    private final LinkedList<Integer> highs = new LinkedList<>();
    private final LinkedList<Integer> lows = new LinkedList<>();

    private SearchState currentSearchState = SearchState.BOTH;
    private final LinkedList<TreeSet<Num>> succeedingLowsAtLastReversal = new LinkedList<>();
    private TreeSet<Num> succeedingLowsSinceLastReversal = new TreeSet<>();

    private final LinkedList<TreeSet<Num>> succeedingHighsAtLastReversal = new LinkedList<>();
    private TreeSet<Num> succeedingHighsSinceLastReversal = new TreeSet<>();

    private final LinkedList<Num> lowestAtLastReversal = new LinkedList<>();
    private Num lowestSinceLastReversal;
    private final LinkedList<Num> highestAtLastReversal = new LinkedList<>();
    private Num highestSinceLastReversal;

    public ReversalComputationState(HighPriceIndicator highPriceIndicator, LowPriceIndicator lowPriceIndicator) {

        this.highPriceIndicator = highPriceIndicator;
        this.lowPriceIndicator = lowPriceIndicator;
    }

    public void update(int index) {
        Num currentHighPrice = highPriceIndicator.getValue(index);
        Num currentLowPrice = lowPriceIndicator.getValue(index);

        switch (currentSearchState) {
            case BOTH:
                searchStart(currentHighPrice, currentLowPrice, index);
                break;
            case LOW:
                searchLow(currentHighPrice, currentLowPrice, index);
                break;
            case HIGH:
                searchHigh(currentHighPrice, currentLowPrice, index);
                break;
            default:
                throw new IllegalStateException("Unknown state: " + currentSearchState);
        }
        succeedingLowsSinceLastReversal.add(currentLowPrice);
        succeedingLowsSinceLastReversal = new TreeSet<>(succeedingLowsSinceLastReversal.headSet(currentLowPrice, true));
        succeedingHighsSinceLastReversal.add(currentHighPrice);
        succeedingHighsSinceLastReversal = new TreeSet<>(succeedingHighsSinceLastReversal.tailSet(currentHighPrice, true));
        lowestSinceLastReversal = lowestSinceLastReversal == null ? currentLowPrice : currentLowPrice.min(lowestSinceLastReversal);
        highestSinceLastReversal = highestSinceLastReversal == null ? currentHighPrice : currentHighPrice.max(highestSinceLastReversal);
    }

    private void searchLow(Num currentHighPrice, Num currentLowPrice, int index) {
        Num lastConfirmedHigh = highs.isEmpty() ? null : highPriceIndicator.getValue(highs.getLast());
        if (currentHighPrice.isGreaterThan(lastConfirmedHigh)) {
            highs.removeLast();

            revertLastReversal();

            Num lastConfirmedLow = lows.isEmpty() ? null : lowPriceIndicator.getValue(lows.getLast());
            if (lastConfirmedLow == null) {
                currentSearchState = SearchState.BOTH;
                searchStart(currentHighPrice, currentLowPrice, index);
            } else {
                currentSearchState = SearchState.HIGH;
                searchHigh(currentHighPrice, currentLowPrice, index);
            }
        } else {
            if (currentLowPrice.isLessThan(lowestSinceLastReversal)) {
                boolean confirmed = succeedingHighsSinceLastReversal.tailSet(currentHighPrice).size() >= CONFIRMATIONS;
                if (confirmed) {
                    lows.add(index);
                    updateStateTo(currentHighPrice, currentLowPrice, SearchState.HIGH);
                }
            }
        }
    }

    private void searchHigh(Num currentHighPrice, Num currentLowPrice, int index) {
        Num lastConfirmedLow = lows.isEmpty() ? null : lowPriceIndicator.getValue(lows.getLast());
        if (currentLowPrice.isLessThan(lastConfirmedLow)) {
            lows.removeLast();

            revertLastReversal();

            Num lastConfirmedHigh = highs.isEmpty() ? null : highPriceIndicator.getValue(highs.getLast());
            if (lastConfirmedHigh == null) {
                currentSearchState = SearchState.BOTH;
                searchStart(currentHighPrice, currentLowPrice, index);
            } else {
                currentSearchState = SearchState.LOW;
                searchLow(currentHighPrice, currentLowPrice, index);
            }
        } else {
            if (currentHighPrice.isGreaterThan(highestSinceLastReversal)) {
                boolean confirmed = succeedingLowsSinceLastReversal.headSet(currentLowPrice).size() >= CONFIRMATIONS;
                if (confirmed) {
                    highs.add(index);
                    updateStateTo(currentHighPrice, currentLowPrice, SearchState.LOW);
                }
            }
        }
    }

    private void revertLastReversal() {
        TreeSet<Num> lastLows = succeedingLowsAtLastReversal.removeLast();
        Num currentHighest = succeedingLowsSinceLastReversal.last();
        succeedingLowsSinceLastReversal.addAll(lastLows);
        succeedingLowsSinceLastReversal = new TreeSet<>(lastLows.headSet(currentHighest, true));

        TreeSet<Num> lastHighs = succeedingHighsAtLastReversal.removeLast();
        Num currentLowest = succeedingHighsSinceLastReversal.first();
        succeedingHighsSinceLastReversal.addAll(lastHighs);
        succeedingHighsSinceLastReversal = new TreeSet<>(succeedingHighsSinceLastReversal.tailSet(currentLowest, true));

        lowestSinceLastReversal = lowestSinceLastReversal.min(lowestAtLastReversal.removeLast());
        highestSinceLastReversal = highestSinceLastReversal.max(highestAtLastReversal.removeLast());
    }

    private void updateStateTo(Num currentHighPrice, Num currentLowPrice, SearchState newState) {
        succeedingLowsAtLastReversal.add(succeedingLowsSinceLastReversal);
        succeedingLowsSinceLastReversal = new TreeSet<>();

        succeedingHighsAtLastReversal.add(succeedingHighsSinceLastReversal);
        succeedingHighsSinceLastReversal = new TreeSet<>();

        lowestAtLastReversal.add(lowestSinceLastReversal);
        highestAtLastReversal.add(highestSinceLastReversal);

        lowestSinceLastReversal = currentLowPrice;
        highestSinceLastReversal = currentHighPrice;
        currentSearchState = newState;
    }

    private void searchStart(Num currentHighPrice, Num currentLowPrice, int index) {
        if(highestSinceLastReversal == null || lowestSinceLastReversal == null) {
            return;
        }

        if (currentHighPrice.isGreaterThan(highestSinceLastReversal)) {
                boolean confirmed = succeedingLowsSinceLastReversal.headSet(currentLowPrice).size() >= CONFIRMATIONS;
                if (confirmed) {
                    if (currentLowPrice.isLessThan(lowestSinceLastReversal)) {
                        boolean highAlsoConfirmed = succeedingHighsSinceLastReversal.tailSet(currentHighPrice).size() >= CONFIRMATIONS;
                        if (highAlsoConfirmed) {
                            throw new IllegalStateException("Cannot find lows and highs at the same time");
                        }
                    }
                    highs.add(index);
                    updateStateTo(currentHighPrice, currentLowPrice, SearchState.LOW);
                }
        }
        if (currentLowPrice.isLessThan(lowestSinceLastReversal)) {
            boolean confirmed = succeedingHighsSinceLastReversal.tailSet(currentHighPrice).size() >= CONFIRMATIONS;
            if (confirmed) {
                lows.add(index);
                updateStateTo(currentHighPrice, currentLowPrice, SearchState.HIGH);
            }
        }
    }

    public Collection<Integer> getLows() {
        return lows;
    }

    public Collection<Integer> getHighs() {
        return highs;
    }

    private enum SearchState {
        LOW, HIGH, BOTH;
    }
}
