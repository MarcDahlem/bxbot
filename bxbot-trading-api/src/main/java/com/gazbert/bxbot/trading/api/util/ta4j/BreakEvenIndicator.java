package com.gazbert.bxbot.trading.api.util.ta4j;

import com.google.common.primitives.Ints;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.helpers.TransformIndicator;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

import static org.ta4j.core.num.NaN.NaN;

public class BreakEvenIndicator extends CachedIndicator<Num> {

    private final TransformIndicator breakEvenIndicator;
    private final TreeSet<Integer> sortedBuyIndeces = new TreeSet<>();
    private final TreeSet<Integer> sortedSellIndeces = new TreeSet<>();

    public BreakEvenIndicator(Indicator<Num> indicator, BigDecimal buyFee, BigDecimal sellFee) {
        super(indicator);
        BigDecimal buyFeeFactor = BigDecimal.ONE.add(buyFee);
        BigDecimal sellFeeFactor = BigDecimal.ONE.subtract(sellFee);
        breakEvenIndicator = TransformIndicator.divide(TransformIndicator.multiply(indicator, buyFeeFactor), sellFeeFactor);
    }

    @Override
    protected Num calculate(int index) {
        Integer lastBuyIndex = sortedBuyIndeces.floor(index);
        Integer lastSellIndex = sortedSellIndeces.floor(index);
        if(lastBuyIndex == null) {
            return NaN;
        }

        if (lastSellIndex == null || lastSellIndex <= lastBuyIndex || lastSellIndex == index) {
            return breakEvenIndicator.getValue(lastBuyIndex);
        }
        return NaN;
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
}
