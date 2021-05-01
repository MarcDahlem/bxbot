package com.gazbert.bxbot.trading.api.util.ta4j;

import com.google.common.primitives.Ints;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.TransformIndicator;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.util.TreeSet;

import static org.ta4j.core.num.NaN.NaN;

/* This indicator is only active in a sell phase and when the last trade is still open. */
public class SellIndicator extends CachedIndicator<Num> {

    private final TreeSet<Integer> sortedBuyIndeces = new TreeSet<>();
    private final TreeSet<Integer> sortedSellIndeces = new TreeSet<>();
    private final Indicator<Num> indicator;
    private final boolean useBuyTime;

    protected SellIndicator(Indicator<Num> indicator, boolean useBuyTime) {
        super(indicator);
        this.indicator = indicator;
        this.useBuyTime = useBuyTime;
    }

    @Override
    protected Num calculate(int index) {
        Integer lastBuyIndex = sortedBuyIndeces.floor(index);
        Integer lastSellIndex = sortedSellIndeces.floor(index);
        if (lastBuyIndex == null) {
            return NaN;
        }

        if (lastSellIndex == null || lastSellIndex <= lastBuyIndex || lastSellIndex == index) {
            if (useBuyTime) {
                return indicator.getValue(lastBuyIndex);
            } else {
                return indicator.getValue(index);
            }
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

    public static SellIndicator createBreakEvenIndicator(BarSeries series, BigDecimal buyFee, BigDecimal sellFee) {
        BigDecimal buyFeeFactor = BigDecimal.ONE.add(buyFee);
        BigDecimal sellFeeFactor = BigDecimal.ONE.subtract(sellFee);
        TransformIndicator breakEvenCalculator = TransformIndicator.divide(TransformIndicator.multiply(new HighPriceIndicator(series), buyFeeFactor), sellFeeFactor);
        return new SellIndicator(breakEvenCalculator, true);
    }
}
