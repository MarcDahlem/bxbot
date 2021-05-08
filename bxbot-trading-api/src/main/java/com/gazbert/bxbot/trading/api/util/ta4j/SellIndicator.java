package com.gazbert.bxbot.trading.api.util.ta4j;

import com.google.common.primitives.Ints;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.HighestValueIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.indicators.helpers.TransformIndicator;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.util.TreeSet;

import static org.ta4j.core.num.NaN.NaN;

/* This indicator is only active in a sell phase and when the last trade is still open. */
public class SellIndicator extends TradeBasedIndicator<Num> {

    private final Indicator<Num> indicator;
    private final boolean useBuyTime;

    public SellIndicator(Indicator<Num> indicator, boolean useBuyTime) {
        this(indicator, null, useBuyTime);
    }

    public SellIndicator(Indicator<Num> indicator, TradeBasedIndicator<Num> tradeKnowingIndicator, boolean useBuyTime) {
        super(indicator.getBarSeries(), tradeKnowingIndicator);
        this.indicator = indicator;
        this.useBuyTime = useBuyTime;
    }

    @Override
    protected Num calculateNoLastTradeAvailable(int index) {
        return NaN;
    }

    @Override
    protected Num calculateLastTradeWasBuy(int buyIndex, int index) {
        if (useBuyTime) {
            return indicator.getValue(buyIndex);
        } else {
            return new HighestValueIndicator(indicator, index-buyIndex+1).getValue(index);
        }
    }

    @Override
    protected Num calculateLastTradeWasSell(int sellIndex, int index) {
        return NaN;
    }

    public static SellIndicator createBreakEvenIndicator(BarSeries series, BigDecimal buyFee, BigDecimal sellFee) {
        BigDecimal buyFeeFactor = BigDecimal.ONE.add(buyFee);
        BigDecimal sellFeeFactor = BigDecimal.ONE.subtract(sellFee);
        TransformIndicator breakEvenCalculator = TransformIndicator.divide(TransformIndicator.multiply(new HighPriceIndicator(series), buyFeeFactor), sellFeeFactor);
        return new SellIndicator(breakEvenCalculator, true);
    }

    public static SellIndicator createSellLimitIndicator(BarSeries series, BigDecimal limitPercentageUnderCurrentBid, SellIndicator tradeKnowingIndicator) {
        LowPriceIndicator bidPriceIndicator = new LowPriceIndicator(series);
        BigDecimal limitScaleFactor = BigDecimal.ONE.subtract(limitPercentageUnderCurrentBid);
        TransformIndicator sellLimitCalculator = TransformIndicator.multiply(bidPriceIndicator, limitScaleFactor);
        return new SellIndicator(sellLimitCalculator, tradeKnowingIndicator, false);
    }
}
