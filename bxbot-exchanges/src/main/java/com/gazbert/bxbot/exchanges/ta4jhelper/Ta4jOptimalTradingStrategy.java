package com.gazbert.bxbot.exchanges.ta4jhelper;

import com.gazbert.bxbot.trading.api.TradingApiException;
import com.google.common.primitives.Ints;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Rule;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.FixedRule;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class Ta4jOptimalTradingStrategy extends BaseStrategy {

    private Ta4jOptimalTradingStrategy(Rule buyRule, Rule sellRule) throws TradingApiException {
        super("Optimal trading rule", buyRule, sellRule);
    }

    public static Ta4jOptimalTradingStrategy createOptimalTradingStrategy(BarSeries series, Num buyFee, Num sellFee) throws TradingApiException {
        int lastSeenMinimumIndex = -1;
        Num lastSeenMinimum = null;
        int lastSeenMaximumIndex = -1;
        Num lastSeenMaximum = null;

        ArrayList<Integer> buyIndeces = new ArrayList<>();
        ArrayList<Integer> sellIndeces = new ArrayList<>();

        for(int index = series.getBeginIndex(); index <= series.getEndIndex(); index++) {
            Bar bar = series.getBar(index);
            Num askPrice = bar.getHighPrice();
            Num bidPrice = bar.getLowPrice();
            if (lastSeenMinimum == null) {
                lastSeenMinimum = askPrice;
                lastSeenMinimumIndex = index;
            } else {
                if (lastSeenMinimum.isGreaterThan(askPrice)) {
                    createTrade(lastSeenMinimumIndex, lastSeenMinimum, lastSeenMaximumIndex, lastSeenMaximum, buyIndeces, sellIndeces);
                    lastSeenMaximum = null;
                    lastSeenMaximumIndex = -1;
                    lastSeenMinimum = askPrice;
                    lastSeenMinimumIndex = index;
                } else {
                    Num buyFees = lastSeenMinimum.multipliedBy(buyFee);
                    Num minimumPlusFees = lastSeenMinimum.plus(buyFees);
                    Num currentPriceSellFees = bidPrice.multipliedBy(sellFee);
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
                            Num lastMaxPriceSellFees = lastSeenMaximum.multipliedBy(sellFee);
                            Num lastMaxPriceMinusFees = lastSeenMaximum.minus(lastMaxPriceSellFees);
                            Num currentPricePlusBuyFees = bidPrice.plus(bidPrice.multipliedBy(buyFee));
                            if (currentPricePlusBuyFees.isLessThan(lastMaxPriceMinusFees)) {
                                createTrade(lastSeenMinimumIndex, lastSeenMinimum, lastSeenMaximumIndex, lastSeenMaximum, buyIndeces, sellIndeces);
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
        return new Ta4jOptimalTradingStrategy(new FixedRule(Ints.toArray(buyIndeces)), new FixedRule(Ints.toArray(sellIndeces)));
    }

    private static void createTrade(int lastSeenMinimumIndex, Num lastSeenMinimum, int lastSeenMaximumIndex, Num lastSeenMaximum, List<Integer> buyIndeces, List<Integer> sellIndeces) throws TradingApiException {
        if (lastSeenMinimum != null && lastSeenMaximum != null) {
            buyIndeces.add(lastSeenMinimumIndex);
            sellIndeces.add(lastSeenMaximumIndex);
        }
    }
}
