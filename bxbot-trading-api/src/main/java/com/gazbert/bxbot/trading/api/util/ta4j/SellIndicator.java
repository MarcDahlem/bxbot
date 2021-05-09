package com.gazbert.bxbot.trading.api.util.ta4j;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.helpers.*;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

import static org.ta4j.core.num.NaN.NaN;

/* This indicator is only active in a sell phase and when the last trade is still open. */
public class SellIndicator extends TradeBasedIndicator<Num> {

    private final BiFunction<Integer, Integer, Indicator<Num>> buyIndicatorCreator;
    private final BiFunction<Integer, Integer, Indicator<Num>> sellIndicatorCreator;

    public SellIndicator(BarSeries series, BiFunction<Integer, Integer, Indicator<Num>> buyIndicatorCreator) {
        this(series, null, buyIndicatorCreator);
    }

    public SellIndicator(BarSeries series, TradeBasedIndicator<Num> tradeKnowingIndicator, BiFunction<Integer, Integer, Indicator<Num>> buyIndicatorCreator) {
        this(series, tradeKnowingIndicator, buyIndicatorCreator, getNanCreator(series));
    }

    public SellIndicator(BarSeries series, TradeBasedIndicator<Num> tradeKnowingIndicator, BiFunction<Integer, Integer, Indicator<Num>> buyIndicatorCreator, BiFunction<Integer, Integer, Indicator<Num>> sellIndicatorCreator) {
        super(series, tradeKnowingIndicator);
        this.buyIndicatorCreator = buyIndicatorCreator;
        this.sellIndicatorCreator = sellIndicatorCreator;
    }

    @Override
    protected Num calculateNoLastTradeAvailable(int index) {
        return calculateLastTradeWasSell(0, index);
    }

    @Override
    protected Num calculateLastTradeWasBuy(int buyIndex, int index) {
        return buyIndicatorCreator.apply(buyIndex, index).getValue(index);
    }

    @Override
    protected Num calculateLastTradeWasSell(int sellIndex, int index) {
        return sellIndicatorCreator.apply(sellIndex, index).getValue(index);
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
        return new SellIndicator(series, tradeKnowingIndicator, (buyIndex, index) -> new HighestValueIndicator(sellLimitCalculator, index - buyIndex + 1));
    }

    public static SellIndicator createLowestSinceLastSellIndicator(Indicator<Num> originalIndicator, int maxLookback, SellIndicator tradeKnowingIndicator) {

        final BiFunction<Integer, Integer, Indicator<Num>> lowestSinceCreator = (Integer sellIndex, Integer index) -> {
            int lookbackSinceLastSell = index - sellIndex;
            int limitedLookback = Math.min(lookbackSinceLastSell, maxLookback);
            return new LowestValueIndicator(originalIndicator, limitedLookback+1);
        };

        return new SellIndicator(
                originalIndicator.getBarSeries(),
                tradeKnowingIndicator,
                getNanCreator(originalIndicator.getBarSeries()),
                lowestSinceCreator);
    }

    private static BiFunction<Integer, Integer, Indicator<Num>> getNanCreator(BarSeries series) {
        return (Integer startIndex, Integer index) -> new ConstantIndicator<>(series, NaN);
    }
}
