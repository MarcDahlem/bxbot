package com.gazbert.bxbot.trading.api.util.ta4j;

import static org.ta4j.core.num.NaN.NaN;

import java.math.BigDecimal;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.ConstantIndicator;
import org.ta4j.core.indicators.helpers.HighestValueIndicator;
import org.ta4j.core.indicators.helpers.LowestValueIndicator;
import org.ta4j.core.indicators.helpers.TransformIndicator;
import org.ta4j.core.num.Num;

/* This indicator is only active in a sell phase and when the last trade is still open. */
public class ExitIndicator extends TradeBasedIndicator<Num> {

    private final Function<Integer, Function<MarketEnterType, Function<Integer, Indicator<Num>>>> enterIndicatorCreator;
    private final Function<Integer, Function<Integer, Indicator<Num>>> exitIndicatorCreator;

    public ExitIndicator(BarSeries series, Function<Integer, Function<MarketEnterType, Function<Integer, Indicator<Num>>>> enterIndicatorCreator) {
        this(series, null, enterIndicatorCreator);
    }

    public ExitIndicator(BarSeries series, TradeBasedIndicator<Num> tradeKnowingIndicator, Function<Integer, Function<MarketEnterType, Function<Integer, Indicator<Num>>>> enterIndicatorCreator) {
        this(series, tradeKnowingIndicator, enterIndicatorCreator, getNanCreator(series));
    }

    public ExitIndicator(BarSeries series, TradeBasedIndicator<Num> tradeKnowingIndicator, Function<Integer, Function<MarketEnterType, Function<Integer, Indicator<Num>>>> enterIndicatorCreator, Function<Integer, Function<Integer, Indicator<Num>>> exitIndicatorCreator) {
        super(series, tradeKnowingIndicator);
        this.enterIndicatorCreator = enterIndicatorCreator;
        this.exitIndicatorCreator = exitIndicatorCreator;
    }

    @Override
    protected Num calculateNoLastTradeAvailable(int index) {
        return calculateLastTradeWasExit(0, index);
    }

    @Override
    protected Num calculateLastTradeWasEnter(int enterIndex, MarketEnterType enterType, int index) {
        return enterIndicatorCreator.apply(enterIndex).apply(enterType).apply(index).getValue(index);
    }

    @Override
    protected Num calculateLastTradeWasExit(int exitIndex, int index) {
        return exitIndicatorCreator.apply(exitIndex).apply(index).getValue(index);
    }

    public static ExitIndicator createBreakEvenIndicator(BarSeries series, BigDecimal enterFee, BigDecimal exitFee) {
        return createBreakEvenIndicator(series, null, enterFee, exitFee);
    }

    public static ExitIndicator createBreakEvenIndicator(BarSeries series, ExitIndicator tradeKnowingIndicator, BigDecimal enterFee, BigDecimal exitFee) {
        BigDecimal enterFeeFactor = BigDecimal.ONE.add(enterFee);
        BigDecimal exitFeeFactor = BigDecimal.ONE.subtract(exitFee);
        ClosePriceIndicator marketPriceIndicator = new ClosePriceIndicator(series);
        TransformIndicator longBreakEvenCalculator = TransformIndicator.divide(TransformIndicator.multiply(marketPriceIndicator, enterFeeFactor), exitFeeFactor);
        TransformIndicator shortBreakEvenCalculator = TransformIndicator.multiply(TransformIndicator.divide(marketPriceIndicator, enterFeeFactor), exitFeeFactor);
        return new ExitIndicator(series, tradeKnowingIndicator,
                buyIndex -> enterType -> index ->
                {
                    TransformIndicator breakEvenCalculator = null;
                    switch (enterType) {
                        case LONG_POSITION:
                            breakEvenCalculator = longBreakEvenCalculator;
                            break;
                        case SHORT_POSITION:
                            breakEvenCalculator = shortBreakEvenCalculator;
                            break;
                    }
                    Num value = breakEvenCalculator.getValue(buyIndex);
                    return new ConstantIndicator<>(series, value);
                });
    }

    public static ExitIndicator createSellLimitIndicator(BarSeries series, BigDecimal limitPercentageUnderCurrentMarket, ExitIndicator tradeKnowingIndicator) {
        ClosePriceIndicator closePriceIndicator = new ClosePriceIndicator(series);
        BigDecimal limitScaleFactor = BigDecimal.ONE.subtract(limitPercentageUnderCurrentMarket);
        TransformIndicator sellLimitCalculator = TransformIndicator.multiply(closePriceIndicator, limitScaleFactor);
        return new ExitIndicator(series, tradeKnowingIndicator, entryIndex -> enterType -> index -> {
            if (!enterType.equals(MarketEnterType.LONG_POSITION)) {
                throw new IllegalStateException("Cannot calculate sell limit indicator on short positions");
            }
            return new HighestValueIndicator(sellLimitCalculator, index - entryIndex + 1);
        });
    }

    public static ExitIndicator createShortBuyLimitIndicator(BarSeries series, BigDecimal limitPercentageOverCurrentMarket, ExitIndicator tradeKnowingIndicator) {
        ClosePriceIndicator closePriceIndicator = new ClosePriceIndicator(series);
        BigDecimal limitScaleFactor = BigDecimal.ONE.add(limitPercentageOverCurrentMarket);
        TransformIndicator shortBuyLimitCalculator = TransformIndicator.multiply(closePriceIndicator, limitScaleFactor);
        return new ExitIndicator(series, tradeKnowingIndicator, entryIndex -> enterType -> index -> {
            if (!enterType.equals(MarketEnterType.SHORT_POSITION)) {
                throw new IllegalStateException("Cannot calculate short buy limit indicator on long positions");
            }
            return new LowestValueIndicator(shortBuyLimitCalculator, index - entryIndex + 1);
        });
    }

    public static ExitIndicator createLowestSinceLastExitIndicator(Indicator<Num> originalIndicator, int maxLookback, ExitIndicator tradeKnowingIndicator) {

        final Function<Integer, Function<Integer, Indicator<Num>>> lowestSinceCreator = (Integer sellIndex) -> (Integer index) -> {
            int lookbackSinceLastSell = index - sellIndex;
            int limitedLookback = Math.min(lookbackSinceLastSell, maxLookback);
            return new LowestValueIndicator(originalIndicator, limitedLookback+1);
        };

        return new ExitIndicator(
                originalIndicator.getBarSeries(),
                tradeKnowingIndicator,
                getNanEntryCreator(originalIndicator.getBarSeries()),
                lowestSinceCreator);
    }

    private static Function<Integer, Function<Integer, Indicator<Num>>> getNanCreator(BarSeries series) {
        return (Integer startIndex) -> (Integer index) -> new ConstantIndicator<>(series, NaN);
    }

    private static Function<Integer, Function<MarketEnterType, Function<Integer, Indicator<Num>>>> getNanEntryCreator(BarSeries series) {
        return (Integer startIndex) -> (MarketEnterType enterType) -> (Integer index) -> new ConstantIndicator<>(series, NaN);
    }
}
