package com.gazbert.bxbot.trading.api.util.ta4j;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Rule;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.rules.AbstractRule;

public class StrictBeforeRule extends AbstractRule {

    private final BarSeries series;
    private final Rule firstCondition;
    private final Rule secondCondition;
    private final Rule resetCondition;

    public StrictBeforeRule(BarSeries series, Rule firstCondition, Rule afterCondition, Rule resetCondition) {
        super();
        this.series = series;
        this.firstCondition = firstCondition;
        this.secondCondition = afterCondition;
        this.resetCondition = resetCondition;
    }


    @Override
    public boolean isSatisfied(int index, TradingRecord tradingRecord) {
        if (!secondCondition.isSatisfied(index, tradingRecord)) {
            traceIsSatisfied(index, false);
            return false;
        }

        for (int i=index; i>=series.getBeginIndex(); i--) {
            if(resetCondition.isSatisfied(i, tradingRecord)) {
                traceIsSatisfied(index, false);
                return false;
            }
            if(firstCondition.isSatisfied(i, tradingRecord)) {
                traceIsSatisfied(index, true);
                return true;
            }
        }

        traceIsSatisfied(index, false);
        return false;
    }
}
