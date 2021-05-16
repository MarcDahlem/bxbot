package com.gazbert.bxbot.strategies.helper;

import com.gazbert.bxbot.strategies.IntelligentTrailingStopConfigParams;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.TradingApiException;
import com.gazbert.bxbot.trading.api.util.ta4j.CombineIndicator;
import com.gazbert.bxbot.trading.api.util.ta4j.SellIndicator;
import java.math.BigDecimal;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.helpers.TransformIndicator;
import org.ta4j.core.num.Num;

public class IntelligentTrailIndicator extends CachedIndicator<Num> {
    private final SellIndicator aboveBreakEvenIndicator;
    private final Indicator<Num> minAboveBreakEvenIndicator;
    private final Indicator<Num> breakEvenIndicator;
    private final SellIndicator belowBreakEvenIndicator;

    private IntelligentTrailIndicator(SellIndicator belowBreakEvenIndicator, SellIndicator aboveBreakEvenIndicator, Indicator<Num> minAboveBreakEvenIndicator, Indicator<Num> breakEvenIndicator) {
        super(belowBreakEvenIndicator);

        this.belowBreakEvenIndicator = belowBreakEvenIndicator;
        this.aboveBreakEvenIndicator = aboveBreakEvenIndicator;
        this.minAboveBreakEvenIndicator = minAboveBreakEvenIndicator;
        this.breakEvenIndicator = breakEvenIndicator;
    }


    @Override
    protected Num calculate(int i) {
        Num breakEven = breakEvenIndicator.getValue(i);
        if(minAboveBreakEvenIndicator.getValue(i).isGreaterThanOrEqual(breakEven)) {
            return minAboveBreakEvenIndicator.getValue(i).max(aboveBreakEvenIndicator.getValue(i));
        }
        return belowBreakEvenIndicator.getValue(i);
    }

    public SellIndicator getAboveBreakEvenIndicator() {
        return aboveBreakEvenIndicator;
    }

    public Indicator<Num> getMinAboveBreakEvenIndicator() {
        return minAboveBreakEvenIndicator;
    }

    public SellIndicator getBelowBreakEvenIndicator() {
        return belowBreakEvenIndicator;
    }

    public static IntelligentTrailIndicator createIntelligentTrailIndicator(BarSeries series, IntelligentTrailingStopConfigParams intelligentTrailingStopConfigParams, SellIndicator breakEvenIndicator) throws TradingApiException, ExchangeNetworkException {
        SellIndicator belowBreakEvenIndicator = SellIndicator.createSellLimitIndicator(series, intelligentTrailingStopConfigParams.getCurrentSellStopLimitPercentageBelowBreakEven(), breakEvenIndicator);
        SellIndicator aboveBreakEvenIndicator = SellIndicator.createSellLimitIndicator(series, intelligentTrailingStopConfigParams.getCurrentSellStopLimitPercentageAboveBreakEven(), breakEvenIndicator);
        Indicator<Num> minAboveBreakEvenIndicator = createMinAboveBreakEvenIndicator(series, intelligentTrailingStopConfigParams, breakEvenIndicator);

        return new IntelligentTrailIndicator(belowBreakEvenIndicator, aboveBreakEvenIndicator, minAboveBreakEvenIndicator, breakEvenIndicator);
    }

    private static Indicator<Num> createMinAboveBreakEvenIndicator(BarSeries series, IntelligentTrailingStopConfigParams intelligentTrailingStopConfigParams, SellIndicator breakEvenIndicator) throws TradingApiException, ExchangeNetworkException {
        SellIndicator limitIndicator = SellIndicator.createSellLimitIndicator(series, intelligentTrailingStopConfigParams.getCurrentSellStopLimitPercentageMinimumAboveBreakEven(), breakEvenIndicator);
        BigDecimal minimumAboveBreakEvenAsFactor = BigDecimal.ONE.subtract(intelligentTrailingStopConfigParams.getCurrentSellStopLimitPercentageMinimumAboveBreakEven());
        TransformIndicator minimalDistanceNeededToBreakEven = TransformIndicator.divide(breakEvenIndicator, minimumAboveBreakEvenAsFactor);
        return CombineIndicator.min(limitIndicator, minimalDistanceNeededToBreakEven);
    }
}
