package com.gazbert.bxbot.trading.api.util.ta4j;

import com.gazbert.bxbot.trading.api.Market;
import com.google.common.primitives.Ints;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Indicator;
import org.ta4j.core.Rule;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.FixedRule;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

public class RecordedStrategy extends BaseStrategy {
    private final BreakEvenIndicator breakEvenIndicator;

    protected RecordedStrategy(String name, Rule entryRule, Rule exitRule, BreakEvenIndicator breakEvenIndicator) {
        super(name, entryRule, exitRule);
        this.breakEvenIndicator = breakEvenIndicator;
    }

    public static RecordedStrategy createStrategyFromRecording(String strategyName, BarSeries series, BigDecimal buyFee, BigDecimal sellFee, Collection<Integer> buyIndeces, Collection<Integer> sellIndeces) {
        BreakEvenIndicator beIndicator = new BreakEvenIndicator(new HighPriceIndicator(series), buyFee, sellFee, buyIndeces, sellIndeces);
        return new RecordedStrategy(strategyName, new FixedRule(Ints.toArray(buyIndeces)), new FixedRule(Ints.toArray(sellIndeces)), beIndicator);
    }



    public Map<? extends Indicator<Num>, String> getIndicators() {
        return Map.of(breakEvenIndicator, "break even");
    }
}
