/**
 * The MIT License (MIT)
 *
 * Copyright (c) 2014-2017 Marc de Verdelhan, 2017-2021 Ta4j Organization & respective
 * authors (see AUTHORS)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.gazbert.bxbot.trading.api.util.ta4j;

import com.google.common.primitives.Ints;
import java.util.Map;
import java.util.TreeMap;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.CachedIndicator;

import java.util.TreeSet;

public abstract class TradeBasedIndicator<T> extends CachedIndicator<T> {

    private final TreeMap<Integer, MarketEnterType> sortedEnterIndeces = new TreeMap<>();
    private final TreeSet<Integer> sortedExitIndeces = new TreeSet<>();
    private final TradeBasedIndicator<?> tradeKnowingIndicator;

    public TradeBasedIndicator(BarSeries series) {
        this(series, null);
    }

    public TradeBasedIndicator(BarSeries series, TradeBasedIndicator<?> tradeKnowingIndicator) {
        super(series);
        if (tradeKnowingIndicator != null) {
            this.tradeKnowingIndicator = tradeKnowingIndicator;
        } else {
            this.tradeKnowingIndicator  = this;
        }
    }

    @Override
    protected T calculate(int index) {
        if (isLastEnterForIndexAvailable(index)) {
            if (isLastTradeForIndexAEnter(index)) {
                Map.Entry<Integer, MarketEnterType> lastEnterForIndex = getLastEnterForIndex(index);
                return calculateLastTradeWasEnter(lastEnterForIndex.getKey(), lastEnterForIndex.getValue(), index);
            } else {
                return calculateLastTradeWasExit(getLastExitForIndex(index), index);
            }
        }

        return calculateNoLastTradeAvailable(index);
    }

    private boolean isLastEnterForIndexAvailable(int index) {
        return getLastEnterForIndex(index) != null;
    }

    private boolean isLastTradeForIndexAEnter(int index) {
        Integer lastExitForIndex = getLastExitForIndex(index);
        return lastExitForIndex == null
                || getLastEnterForIndex(index).getKey() > lastExitForIndex;
    }

    private Integer getLastExitForIndex(int index) {
        return tradeKnowingIndicator.sortedExitIndeces.floor(index);
    }

    private Map.Entry<Integer, MarketEnterType> getLastEnterForIndex(int index) {
        return tradeKnowingIndicator.sortedEnterIndeces.floorEntry(index);
    }

    public void registerExitOrderExecution(Integer atIndex) {
        sortedExitIndeces.add(atIndex);
    }

    public void registerEntryOrderExecution(Integer atIndex, MarketEnterType enterType) {
        sortedEnterIndeces.put(atIndex, enterType);
    }

    public int[] getRecordedEnterOrderExecutions() {
        return Ints.toArray(this.sortedEnterIndeces.keySet());
    }

    public int[] getRecordedExitOrderExecutions() {
        return Ints.toArray(this.sortedExitIndeces);
    }

    public Integer getLastRecordedExitIndex() {
        if (sortedExitIndeces.isEmpty()) {
            return null;
        }
        return sortedExitIndeces.last();
    }

    public Integer getLastRecordedEntryIndex() {
        if (sortedEnterIndeces.isEmpty()) {
            return null;
        }
        return sortedEnterIndeces.lastKey();
    }

    protected abstract T calculateNoLastTradeAvailable(int index);

    protected abstract T calculateLastTradeWasEnter(int enterIndex, MarketEnterType enterType, int index);

    protected abstract T calculateLastTradeWasExit(int exitIndex, int index);
}
