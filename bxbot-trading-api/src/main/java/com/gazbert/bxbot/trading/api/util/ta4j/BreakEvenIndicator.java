package com.gazbert.bxbot.trading.api.util.ta4j;

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
    private final TreeSet<Integer> sortedBuyIndeces;
    private final TreeSet<Integer> sortedSellIndeces;

    public BreakEvenIndicator(Indicator<Num> indicator, BigDecimal buyFee, BigDecimal sellFee, Collection<Integer> buyIndeces, Collection<Integer> sellIndices) {
        super(indicator);
        BigDecimal buyFeeFactor = BigDecimal.ONE.add(buyFee);
        BigDecimal sellFeeFactor = BigDecimal.ONE.subtract(sellFee);
        breakEvenIndicator = TransformIndicator.divide(TransformIndicator.multiply(indicator, buyFeeFactor), sellFeeFactor);
        sortedBuyIndeces = new TreeSet<>(buyIndeces);
        sortedSellIndeces = new TreeSet<>(sellIndices);
    }

    @Override
    protected Num calculate(int index) {
        Integer lastBuyIndex = sortedBuyIndeces.floor(index);
        Integer lastSellIndex = sortedSellIndeces.floor(index);
        if(lastBuyIndex == null) {
            return NaN;
        }

        if (lastSellIndex == null || lastSellIndex < lastBuyIndex) {
            return breakEvenIndicator.getValue(lastBuyIndex);
        }
        return NaN;
    }
}
