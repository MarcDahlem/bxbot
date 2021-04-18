package com.gazbert.bxbot.exchanges.ta4objects;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;

public class Ta4jOptimalTradingStrategy extends BaseStrategy {
    private static final TA4JRecordingRule buyRule = new TA4JRecordingRule();
    private static final TA4JRecordingRule sellRule = new TA4JRecordingRule();

    public Ta4jOptimalTradingStrategy(BarSeries series, BigDecimal buyFee, BigDecimal sellFee) {
        super("Optimal trading rule", buyRule, sellRule);
        this.calculateOptimalTrades(series, series.numOf(buyFee), series.numOf(sellFee));
    }

    private void calculateOptimalTrades(BarSeries series, Num buyFee, Num sellFee) {
        int lastSeenMinimumIndex = -1;
        Num lastSeenMinimum = null;
        int lastSeenMaximumIndex = -1;
        Num lastSeenMaximum = null;

        for(int index = series.getBeginIndex(); index <= series.getEndIndex(); index++) {
            Bar bar = series.getBar(index);
            Num askPrice = bar.getHighPrice();
            Num bidPrice = bar.getLowPrice();
            if (lastSeenMinimum == null) {
                lastSeenMinimum = askPrice;
                lastSeenMinimumIndex = index;
            } else {
                if (lastSeenMinimum.isGreaterThan(askPrice)) {
                    createTrade(lastSeenMinimumIndex, lastSeenMinimum, lastSeenMaximumIndex, lastSeenMaximum);
                    lastSeenMaximum = null;
                    lastSeenMaximumIndex = -1;
                    lastSeenMinimum = askPrice;
                    lastSeenMinimumIndex = index;
                } else {
                    Num minimumPlusFees = lastSeenMinimum.plus(lastSeenMinimum.multipliedBy(buyFee));
                    Num currentPriceMinusFees = bidPrice.minus(bidPrice.multipliedBy(sellFee));
                    if(lastSeenMaximum == null) {
                        if(currentPriceMinusFees.isGreaterThan(minimumPlusFees)) {
                            lastSeenMaximum = bidPrice;
                            lastSeenMaximumIndex = index;
                        }
                    } else {
                        if(lastSeenMaximum.isLessThanOrEqual(bidPrice)) {
                            lastSeenMaximum = bidPrice;
                            lastSeenMaximumIndex = index;
                        } else {
                            if (currentPriceMinusFees.isLessThan(minimumPlusFees)) {
                                createTrade(lastSeenMinimumIndex, lastSeenMinimum, lastSeenMaximumIndex, lastSeenMaximum);
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
    }

    private void createTrade(int lastSeenMinimumIndex, Num lastSeenMinimum, int lastSeenMaximumIndex, Num lastSeenMaximum) {
        if (lastSeenMinimum != null && lastSeenMaximum != null) {
            buyRule.addTrigger(lastSeenMinimumIndex);
            sellRule.addTrigger(lastSeenMaximumIndex);
        }
    }
}
