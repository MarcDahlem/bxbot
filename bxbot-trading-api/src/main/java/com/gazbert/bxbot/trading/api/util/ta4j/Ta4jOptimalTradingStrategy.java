package com.gazbert.bxbot.trading.api.util.ta4j;

import com.gazbert.bxbot.trading.api.TradingApiException;
import java.math.BigDecimal;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.num.Num;

public class Ta4jOptimalTradingStrategy extends RecordedStrategy {


    protected Ta4jOptimalTradingStrategy(String name, ExitIndicator breakEvenIndicator) {
        super(name, breakEvenIndicator);
    }

    public static RecordedStrategy createOptimalTradingStrategy(BarSeries series, BigDecimal buyFee, BigDecimal sellFee) throws TradingApiException {
        int lastSeenMinimumIndex = -1;
        Num lastSeenMinimum = null;
        int lastSeenMaximumIndex = -1;
        Num lastSeenMaximum = null;

        ExitIndicator beIndicator = ExitIndicator.createBreakEvenIndicator(series, buyFee, sellFee);

        for(int index = series.getBeginIndex(); index <= series.getEndIndex(); index++) {
            Bar bar = series.getBar(index);
            Num closePrice = bar.getClosePrice();
            if (lastSeenMinimum == null) {
                lastSeenMinimum = closePrice;
                lastSeenMinimumIndex = index;
            } else {
                if (lastSeenMinimum.isGreaterThan(closePrice)) {
                    createTrade(lastSeenMinimumIndex, lastSeenMinimum, lastSeenMaximumIndex, lastSeenMaximum, beIndicator);
                    lastSeenMaximum = null;
                    lastSeenMaximumIndex = -1;
                    lastSeenMinimum = closePrice;
                    lastSeenMinimumIndex = index;
                } else {
                    Num buyFees = lastSeenMinimum.multipliedBy(series.numOf(buyFee));
                    Num minimumPlusFees = lastSeenMinimum.plus(buyFees);
                    Num currentPriceSellFees = closePrice.multipliedBy(series.numOf(sellFee));
                    Num currentPriceMinusFees = closePrice.minus(currentPriceSellFees);
                    if(lastSeenMaximum == null) {
                        if(currentPriceMinusFees.isGreaterThan(minimumPlusFees)) {
                            lastSeenMaximum = closePrice;
                            lastSeenMaximumIndex = index;
                        }
                    } else {
                        if(closePrice.isGreaterThanOrEqual(lastSeenMaximum)) {
                            lastSeenMaximum = closePrice;
                            lastSeenMaximumIndex = index;
                        } else {
                            Num lastMaxPriceSellFees = lastSeenMaximum.multipliedBy(series.numOf(sellFee));
                            Num lastMaxPriceMinusFees = lastSeenMaximum.minus(lastMaxPriceSellFees);
                            Num currentPricePlusBuyFees = closePrice.plus(closePrice.multipliedBy(series.numOf(buyFee)));
                            if (currentPricePlusBuyFees.isLessThan(lastMaxPriceMinusFees)) {
                                createTrade(lastSeenMinimumIndex, lastSeenMinimum, lastSeenMaximumIndex, lastSeenMaximum, beIndicator);
                                lastSeenMaximum = null;
                                lastSeenMaximumIndex = -1;
                                lastSeenMinimum = closePrice;
                                lastSeenMinimumIndex = index;
                            }
                        }
                    }
                }
            }
        }
        return RecordedStrategy.createStrategyFromRecording("Optimal trading strategy", beIndicator);
    }

    private static void createTrade(int lastSeenMinimumIndex, Num lastSeenMinimum, int lastSeenMaximumIndex, Num lastSeenMaximum, ExitIndicator beIndicator) throws TradingApiException {
        if (lastSeenMinimum != null && lastSeenMaximum != null) {
            beIndicator.registerEntryOrderExecution(lastSeenMinimumIndex, MarketEnterType.LONG_POSITION);
            beIndicator.registerExitOrderExecution(lastSeenMaximumIndex);
        }
    }
}
