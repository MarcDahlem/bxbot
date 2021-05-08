package com.gazbert.bxbot.trading.api.util.ta4j;

import com.google.common.primitives.Ints;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.helpers.*;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.util.TreeSet;
import java.util.function.BiFunction;

import static org.ta4j.core.num.NaN.NaN;

/* This indicator is only active in a sell phase and when the last trade is still open. */
public class SellIndicator extends TradeBasedIndicator<Num> {

    private final BiFunction<Integer, Integer, Indicator<Num>> buyIndicatorCreator;

    public SellIndicator(BarSeries series, BiFunction<Integer, Integer, Indicator<Num>> buyIndicatorCreator) {
        this(series, null, buyIndicatorCreator);
    }

    public SellIndicator(BarSeries series, TradeBasedIndicator<Num> tradeKnowingIndicator, BiFunction<Integer, Integer, Indicator<Num>> buyIndicatorCreator) {
        super(series, tradeKnowingIndicator);
        this.buyIndicatorCreator = buyIndicatorCreator;
    }

    @Override
    protected Num calculateNoLastTradeAvailable(int index) {
        return NaN;
    }

    @Override
    protected Num calculateLastTradeWasBuy(int buyIndex, int index) {
        return buyIndicatorCreator.apply(buyIndex, index).getValue(index);
    }

    @Override
    protected Num calculateLastTradeWasSell(int sellIndex, int index) {
        return NaN;
    }

    public static SellIndicator createBreakEvenIndicator(BarSeries series, BigDecimal buyFee, BigDecimal sellFee) {
        BigDecimal buyFeeFactor = BigDecimal.ONE.add(buyFee);
        BigDecimal sellFeeFactor = BigDecimal.ONE.subtract(sellFee);
        TransformIndicator breakEvenCalculator = TransformIndicator.divide(TransformIndicator.multiply(new HighPriceIndicator(series), buyFeeFactor), sellFeeFactor);
        return new SellIndicator(series, (buyIndex, index) -> new ConstantIndicator<>(series, breakEvenCalculator.getValue(buyIndex)));
    }

    public static SellIndicator createSellLimitIndicator(BarSeries series, BigDecimal limitPercentageUnderCurrentBid, SellIndicator tradeKnowingIndicator) {
        LowPriceIndicator bidPriceIndicator = new LowPriceIndicator(series);
        BigDecimal limitScaleFactor = BigDecimal.ONE.subtract(limitPercentageUnderCurrentBid);
        TransformIndicator sellLimitCalculator = TransformIndicator.multiply(bidPriceIndicator, limitScaleFactor);
        return new SellIndicator(series, tradeKnowingIndicator, (buyIndex, index) -> new HighestValueIndicator(sellLimitCalculator, index-buyIndex+1));
    }
}
