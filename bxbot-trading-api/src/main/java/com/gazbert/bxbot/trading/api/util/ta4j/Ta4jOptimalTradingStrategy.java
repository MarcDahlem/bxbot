package com.gazbert.bxbot.trading.api.util.ta4j;

import com.gazbert.bxbot.trading.api.TradingApiException;
import com.google.common.primitives.Ints;
import org.ta4j.core.*;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.FixedRule;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Ta4jOptimalTradingStrategy extends RecordedStrategy {


    protected Ta4jOptimalTradingStrategy(String name, SellIndicator breakEvenIndicator) {
        super(name, breakEvenIndicator);
    }

    public static RecordedStrategy createOptimalTradingStrategy(BarSeries series, BigDecimal buyFee, BigDecimal sellFee) throws TradingApiException {
        int lastSeenMinimumIndex = -1;
        Num lastSeenMinimum = null;
        int lastSeenMaximumIndex = -1;
        Num lastSeenMaximum = null;

        SellIndicator beIndicator = SellIndicator.createBreakEvenIndicator(series, buyFee, sellFee);

        for(int index = series.getBeginIndex(); index <= series.getEndIndex(); index++) {
            Bar bar = series.getBar(index);
            Num askPrice = bar.getHighPrice();
            Num bidPrice = bar.getLowPrice();
            if (lastSeenMinimum == null) {
                lastSeenMinimum = askPrice;
                lastSeenMinimumIndex = index;
            } else {
                if (lastSeenMinimum.isGreaterThan(askPrice)) {
                    createTrade(lastSeenMinimumIndex, lastSeenMinimum, lastSeenMaximumIndex, lastSeenMaximum, beIndicator);
                    lastSeenMaximum = null;
                    lastSeenMaximumIndex = -1;
                    lastSeenMinimum = askPrice;
                    lastSeenMinimumIndex = index;
                } else {
                    Num buyFees = lastSeenMinimum.multipliedBy(series.numOf(buyFee));
                    Num minimumPlusFees = lastSeenMinimum.plus(buyFees);
                    Num currentPriceSellFees = bidPrice.multipliedBy(series.numOf(sellFee));
                    Num currentPriceMinusFees = bidPrice.minus(currentPriceSellFees);
                    if(lastSeenMaximum == null) {
                        if(currentPriceMinusFees.isGreaterThan(minimumPlusFees)) {
                            lastSeenMaximum = bidPrice;
                            lastSeenMaximumIndex = index;
                        }
                    } else {
                        if(bidPrice.isGreaterThanOrEqual(lastSeenMaximum)) {
                            lastSeenMaximum = bidPrice;
                            lastSeenMaximumIndex = index;
                        } else {
                            Num lastMaxPriceSellFees = lastSeenMaximum.multipliedBy(series.numOf(sellFee));
                            Num lastMaxPriceMinusFees = lastSeenMaximum.minus(lastMaxPriceSellFees);
                            Num currentPricePlusBuyFees = bidPrice.plus(bidPrice.multipliedBy(series.numOf(buyFee)));
                            if (currentPricePlusBuyFees.isLessThan(lastMaxPriceMinusFees)) {
                                createTrade(lastSeenMinimumIndex, lastSeenMinimum, lastSeenMaximumIndex, lastSeenMaximum, beIndicator);
                                lastSeenMaximum = null;
                                lastSeenMaximumIndex = -1;
                                lastSeenMinimum = askPrice;
                                lastSeenMinimumIndex = index;
                            }
                        }
                    }
                }
            }
        }
        return RecordedStrategy.createStrategyFromRecording("Optimal trading strategy", beIndicator);
    }

    private static void createTrade(int lastSeenMinimumIndex, Num lastSeenMinimum, int lastSeenMaximumIndex, Num lastSeenMaximum, SellIndicator beIndicator) throws TradingApiException {
        if (lastSeenMinimum != null && lastSeenMaximum != null) {
            beIndicator.registerBuyOrderExecution(lastSeenMinimumIndex);
            beIndicator.registerSellOrderExecution(lastSeenMaximumIndex);
        }
    }
}