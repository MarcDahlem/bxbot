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
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.CachedIndicator;

import java.util.TreeSet;

public abstract class TradeBasedIndicator<T> extends CachedIndicator<T> {

    private final TreeSet<Integer> sortedBuyIndeces = new TreeSet<>();
    private final TreeSet<Integer> sortedSellIndeces = new TreeSet<>();
    private final TradeBasedIndicator<T> tradeKnowingIndicator;

    public TradeBasedIndicator(BarSeries series) {
        this(series, null);
    }

    public TradeBasedIndicator(BarSeries series, TradeBasedIndicator<T> tradeKnowingIndicator) {
        super(series);
        if (tradeKnowingIndicator != null) {
            this.tradeKnowingIndicator = tradeKnowingIndicator;
        } else {
            this.tradeKnowingIndicator  = this;
        }
    }

    @Override
    protected T calculate(int index) {
        if (isLastBuyForIndexAvailable(index)) {
            if (isLastTradeForIndexABuy(index)) {
                return calculateLastTradeWasBuy(getLastBuyForIndex(index), index);
            } else {
                return calculateLastTradeWasSell(getLastSellForIndex(index), index);
            }
        }

        return calculateNoLastTradeAvailable(index);
    }

    private boolean isLastBuyForIndexAvailable(int index) {
        return getLastBuyForIndex(index) != null;
    }

    private boolean isLastTradeForIndexABuy(int index) {
        Integer lastSellForIndex = getLastSellForIndex(index);
        return lastSellForIndex == null
                || getLastBuyForIndex(index) > lastSellForIndex;
    }

    private Integer getLastSellForIndex(int index) {
        return tradeKnowingIndicator.sortedSellIndeces.floor(index);
    }

    private Integer getLastBuyForIndex(int index) {
        return tradeKnowingIndicator.sortedBuyIndeces.floor(index);
    }

    public void registerSellOrderExecution(Integer atIndex) {
        sortedSellIndeces.add(atIndex);
    }

    public void registerBuyOrderExecution(Integer atIndex) {
        sortedBuyIndeces.add(atIndex);
    }

    public int[] getRecordedBuyOrderExecutions() {
        return Ints.toArray(this.sortedBuyIndeces);
    }

    public int[] getRecordedSellOrderExecutions() {
        return Ints.toArray(this.sortedSellIndeces);
    }

    public Integer getLastRecordedSellIndex() {
        if (sortedSellIndeces.isEmpty()) {
            return null;
        }
        return sortedSellIndeces.last();
    }

    protected abstract T calculateNoLastTradeAvailable(int index);

    protected abstract T calculateLastTradeWasBuy(int buyIndex, int index);

    protected abstract T calculateLastTradeWasSell(int sellIndex, int index);

}
