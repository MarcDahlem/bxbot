package com.gazbert.bxbot.trading.api.util.ta4j;

import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentSkipListSet;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.num.Num;

public class ReversalComputationState {


    public static final int CONFIRMATIONS = 2;
    private final HighPriceIndicator highPriceIndicator;
    private final LowPriceIndicator lowPriceIndicator;

    private final ConcurrentLinkedDeque<Integer> highs = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Integer> lows = new ConcurrentLinkedDeque<>();


    private SearchState currentSearchState = SearchState.BOTH;

    private volatile Num lastLow;
    private volatile Num lastHigh;
    private volatile Integer lastHighIndex;
    private volatile Integer lastLowIndex;

    private volatile ConcurrentSkipListSet<Num> lastLowConfirmations = new ConcurrentSkipListSet<>();
    private volatile ConcurrentSkipListSet<Num> lastHighConfirmations = new ConcurrentSkipListSet<>();

    private final ConcurrentLinkedDeque<ConcurrentSkipListSet<Num>> cacheOfLastLowConfirmations = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<ConcurrentSkipListSet<Num>> cacheOfLastHighConfirmations = new ConcurrentLinkedDeque<>();

    private final ConcurrentLinkedDeque<Num> cacheOfLastHighs = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Integer> cacheOfLastHighIndeces = new ConcurrentLinkedDeque<>();

    private final ConcurrentLinkedDeque<Num> cacheOfLastLows = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Integer> cacheOfLastLowIndeces = new ConcurrentLinkedDeque<>();

    public ReversalComputationState(HighPriceIndicator highPriceIndicator, LowPriceIndicator lowPriceIndicator) {

        this.highPriceIndicator = highPriceIndicator;
        this.lowPriceIndicator = lowPriceIndicator;
    }

    public void update(int index) {
        Num currentHighPrice = highPriceIndicator.getValue(index);
        Num currentLowPrice = lowPriceIndicator.getValue(index);

        if (lastLow == null || currentLowPrice.compareTo(lastLow) < 0) {
            this.lastLow = currentLowPrice;
            lastLowIndex = index;
            lastLowConfirmations.clear();
        }
        if (lastHigh == null || currentHighPrice.compareTo(lastHigh) > 0) {
            this.lastHigh = currentHighPrice;
            lastHighIndex = index;
            lastHighConfirmations.clear();
        }

        if (lastHighConfirmations.isEmpty() || currentLowPrice.isLessThan(lastHighConfirmations.first())) {
            lastHighConfirmations.add(currentLowPrice);
        }

        if (lastLowConfirmations.isEmpty() || currentHighPrice.isGreaterThan(lastLowConfirmations.last())) {
            lastLowConfirmations.add(currentHighPrice);
        }


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
    }

    private void searchLow(Num currentHighPrice, Num currentLowPrice, int index) {
        boolean lastLowConfirmed = lastLowConfirmations.size() > CONFIRMATIONS;

        if (lastLowConfirmed) {
            lows.add(lastLowIndex);
            finishLowSearching(currentHighPrice, currentLowPrice, index);
        } else {
            if (lastHighIndex == index) {
                // we are searching for a new low, but found a new high
                // Revert the old high
                resetLastHigh();
                Num lastConfirmedLow = lows.isEmpty() ? null : lowPriceIndicator.getValue(lows.getLast());
                if (lastConfirmedLow == null) {
                    currentSearchState = SearchState.BOTH;
                    searchStart(currentHighPrice, currentLowPrice, index);
                } else {
                    currentSearchState = SearchState.HIGH;
                    searchHigh(currentHighPrice, currentLowPrice, index);
                }
            }
        }
    }

    private void searchHigh(Num currentHighPrice, Num currentLowPrice, int index) {
        boolean lastHighConfirmed = lastHighConfirmations.size() > CONFIRMATIONS;

        if (lastHighConfirmed) {
            highs.add(lastHighIndex);
            finishHighSearching(currentHighPrice, currentLowPrice, index);
        } else {
            if (lastLowIndex == index) {
                // we are searching for a new high, but found a new low
                // Revert the old low
                resetLastLow();
                Num lastConfirmedHigh = highs.isEmpty() ? null : highPriceIndicator.getValue(highs.getLast());
                if (lastConfirmedHigh == null) {
                    currentSearchState = SearchState.BOTH;
                    searchStart(currentHighPrice, currentLowPrice, index);
                } else {
                    currentSearchState = SearchState.LOW;
                    searchLow(currentHighPrice, currentLowPrice, index);
                }
            }
        }
    }

    private void searchStart(Num currentHighPrice, Num currentLowPrice, int index) {
        boolean lastHighConfirmed = lastHighConfirmations.size() > CONFIRMATIONS;
        boolean lastLowConfirmed = lastLowConfirmations.size() > CONFIRMATIONS;
        if (lastHighConfirmed && lastLowConfirmed) {
            throw new IllegalStateException("Cannot find lows and highs at the same time");
        }

        if (lastHighConfirmed) {
            highs.add(lastHighIndex);
            finishHighSearching(currentHighPrice, currentLowPrice, index);
        }

        if (lastLowConfirmed) {
            lows.add(lastLowIndex);
            finishLowSearching(currentHighPrice, currentLowPrice, index);
        }
    }


    private void resetLastLow() {
        lows.removeLast();
        Integer highIndexAtLastLow = cacheOfLastHighIndeces.removeLast();
        Num highAtLastLow = cacheOfLastHighs.removeLast();
        ConcurrentSkipListSet<Num> confirmationsAtLastHigh = cacheOfLastHighConfirmations.removeLast();

        if (highAtLastLow.compareTo(lastHigh) >= 0) {
            lastHigh = highAtLastLow;
            lastHighIndex = highIndexAtLastLow;

            ConcurrentSkipListSet<Num> newHighConfirmations = new ConcurrentSkipListSet<>();
            newHighConfirmations.addAll(confirmationsAtLastHigh);
            newHighConfirmations.addAll(lastHighConfirmations.headSet(confirmationsAtLastHigh.first()));
            lastHighConfirmations = newHighConfirmations;
        }
    }

    private void finishLowSearching(Num currentHighPrice, Num currentLowPrice, int index) {
        cacheOfLastHighs.add(lastHigh);
        cacheOfLastHighIndeces.add(lastHighIndex);
        cacheOfLastHighConfirmations.add(lastHighConfirmations);

        lastHigh = currentHighPrice;
        lastHighIndex = index;
        lastHighConfirmations = new ConcurrentSkipListSet<>();
        lastHighConfirmations.add(currentLowPrice);

        currentSearchState = SearchState.HIGH;
    }

    private void finishHighSearching(Num currentHighPrice, Num currentLowPrice, int index) {
        cacheOfLastLows.add(lastLow);
        cacheOfLastLowIndeces.add(lastLowIndex);
        cacheOfLastLowConfirmations.add(lastLowConfirmations);

        lastLow = currentLowPrice;
        lastLowIndex = index;
        lastLowConfirmations = new ConcurrentSkipListSet<>();
        lastLowConfirmations.add(currentHighPrice);

        currentSearchState = SearchState.LOW;
    }

    private void resetLastHigh() {
        highs.removeLast();
        Integer lowIndexAtLastHigh = cacheOfLastLowIndeces.removeLast();
        Num lowAtLastHigh = cacheOfLastLows.removeLast();
        ConcurrentSkipListSet<Num> confirmationsAtLastLow = cacheOfLastLowConfirmations.removeLast();

        if (lowAtLastHigh.compareTo(lastLow) <= 0) {
            lastLow = lowAtLastHigh;
            lastLowIndex = lowIndexAtLastHigh;

            ConcurrentSkipListSet<Num> newLowConfirmations = new ConcurrentSkipListSet<>();
            newLowConfirmations.addAll(confirmationsAtLastLow);
            newLowConfirmations.addAll(lastLowConfirmations.tailSet(confirmationsAtLastLow.last()));
            lastLowConfirmations = newLowConfirmations;
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
