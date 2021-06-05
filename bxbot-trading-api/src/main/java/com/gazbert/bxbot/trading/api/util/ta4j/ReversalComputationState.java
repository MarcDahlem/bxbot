package com.gazbert.bxbot.trading.api.util.ta4j;

import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.num.Num;

import java.util.Collection;
import java.util.NavigableSet;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentSkipListSet;

public class ReversalComputationState {


    public static final int CONFIRMATIONS = 3;
    private final HighPriceIndicator highPriceIndicator;
    private final LowPriceIndicator lowPriceIndicator;

    private final ConcurrentLinkedDeque<Integer> highs = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Integer> lows = new ConcurrentLinkedDeque<>();

    private SearchState currentSearchState = SearchState.BOTH;
    private final ConcurrentLinkedDeque<ConcurrentSkipListSet<Num>> succeedingLowsAtLastReversal = new ConcurrentLinkedDeque<>();
    private volatile ConcurrentSkipListSet<Num> succeedingLowsSinceLastReversal = new ConcurrentSkipListSet<>();

    private final ConcurrentLinkedDeque<ConcurrentSkipListSet<Num>> succeedingHighsAtLastReversal = new ConcurrentLinkedDeque<>();
    private volatile ConcurrentSkipListSet<Num> succeedingHighsSinceLastReversal = new ConcurrentSkipListSet<>();

    private final ConcurrentLinkedDeque<Num> lowestAtLastReversal = new ConcurrentLinkedDeque<>();
    private volatile Num lowestSinceLastReversal;
    private final ConcurrentLinkedDeque<Num> highestAtLastReversal = new ConcurrentLinkedDeque<>();
    private volatile Num highestSinceLastReversal;

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
        NavigableSet<Num> newSucceedingLows = succeedingLowsSinceLastReversal.headSet(currentLowPrice, true);
        succeedingLowsSinceLastReversal = new ConcurrentSkipListSet<>(newSucceedingLows);
        succeedingHighsSinceLastReversal.add(currentHighPrice);
        NavigableSet<Num> newSucceedingHighs = succeedingHighsSinceLastReversal.tailSet(currentHighPrice, true);
        succeedingHighsSinceLastReversal = new ConcurrentSkipListSet<>(newSucceedingHighs);
        Num newLowest = lowestSinceLastReversal == null ? currentLowPrice : currentLowPrice.min(this.lowestSinceLastReversal);
        this.lowestSinceLastReversal = newLowest;
        Num newHighest = highestSinceLastReversal == null ? currentHighPrice : currentHighPrice.max(this.highestSinceLastReversal);
        this.highestSinceLastReversal = newHighest;
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
        ConcurrentSkipListSet<Num> lastLows = succeedingLowsAtLastReversal.removeLast();
        Num currentHighest = succeedingLowsSinceLastReversal.last();
        succeedingLowsSinceLastReversal.addAll(lastLows);
        NavigableSet<Num> extendedRevertedSucceedingLows = succeedingLowsSinceLastReversal.headSet(currentHighest, true);
        succeedingLowsSinceLastReversal = new ConcurrentSkipListSet<>(extendedRevertedSucceedingLows);

        ConcurrentSkipListSet<Num> lastHighs = succeedingHighsAtLastReversal.removeLast();
        Num currentLowest = succeedingHighsSinceLastReversal.first();
        succeedingHighsSinceLastReversal.addAll(lastHighs);
        NavigableSet<Num> extendedRevertedSucceedingHighs = succeedingHighsSinceLastReversal.tailSet(currentLowest, true);
        succeedingHighsSinceLastReversal = new ConcurrentSkipListSet<>(extendedRevertedSucceedingHighs);

        Num lastLowest = lowestAtLastReversal.removeLast();
        Num newLowest = lowestSinceLastReversal.min(lastLowest);
        lowestSinceLastReversal = newLowest;
        Num lastHighest = highestAtLastReversal.removeLast();
        Num newHighest = highestSinceLastReversal.max(lastHighest);
        highestSinceLastReversal = newHighest;
    }

    private void updateStateTo(Num currentHighPrice, Num currentLowPrice, SearchState newState) {
        succeedingLowsAtLastReversal.add(succeedingLowsSinceLastReversal);
        succeedingLowsSinceLastReversal = new ConcurrentSkipListSet<>();

        succeedingHighsAtLastReversal.add(succeedingHighsSinceLastReversal);
        succeedingHighsSinceLastReversal = new ConcurrentSkipListSet<>();

        lowestAtLastReversal.add(lowestSinceLastReversal);
        highestAtLastReversal.add(highestSinceLastReversal);

        lowestSinceLastReversal = currentLowPrice;
        highestSinceLastReversal = currentHighPrice;
        currentSearchState = newState;
    }

    private void searchStart(Num currentHighPrice, Num currentLowPrice, int index) {
        if (highestSinceLastReversal == null || lowestSinceLastReversal == null) {
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
