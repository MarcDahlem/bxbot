package com.gazbert.bxbot.strategies.helper;

import com.gazbert.bxbot.strategies.IntelligentTrailingStopConfigParams;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.TradingApiException;
import com.gazbert.bxbot.trading.api.util.ta4j.CombineIndicator;
import com.gazbert.bxbot.trading.api.util.ta4j.ExitIndicator;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.helpers.TransformIndicator;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;

public class IntelligentShortTrailIndicator extends CachedIndicator<Num> {
    private final ExitIndicator belowBreakEvenIndicator;
    private final Indicator<Num> minBelowBreakEvenIndicator;
    private final Indicator<Num> breakEvenIndicator;
    private final ExitIndicator aboveBreakEvenIndicator;

    private IntelligentShortTrailIndicator(ExitIndicator aboveBreakEvenIndicator, ExitIndicator belowBreakEvenIndicator, Indicator<Num> minBelowBreakEvenIndicator, Indicator<Num> breakEvenIndicator) {
        super(aboveBreakEvenIndicator);

        this.aboveBreakEvenIndicator = aboveBreakEvenIndicator;
        this.belowBreakEvenIndicator = belowBreakEvenIndicator;
        this.minBelowBreakEvenIndicator = minBelowBreakEvenIndicator;
        this.breakEvenIndicator = breakEvenIndicator;
    }


    @Override
    protected Num calculate(int i) {
        Num breakEven = breakEvenIndicator.getValue(i);
        if(minBelowBreakEvenIndicator.getValue(i).isLessThanOrEqual(breakEven)) {
            return minBelowBreakEvenIndicator.getValue(i).min(belowBreakEvenIndicator.getValue(i));
        }
        return aboveBreakEvenIndicator.getValue(i);
    }

    public ExitIndicator getBelowBreakEvenIndicator() {
        return belowBreakEvenIndicator;
    }

    public Indicator<Num> getMinBelowBreakEvenIndicator() {
        return minBelowBreakEvenIndicator;
    }

    public ExitIndicator getAboveBreakEvenIndicator() {
        return aboveBreakEvenIndicator;
    }

    public static IntelligentShortTrailIndicator createIntelligentShortTrailIndicator(BarSeries series, IntelligentTrailingStopConfigParams intelligentTrailingStopConfigParams, ExitIndicator breakEvenIndicator) throws TradingApiException, ExchangeNetworkException {
        ExitIndicator aboveBreakEvenIndicator = ExitIndicator.createShortBuyLimitIndicator(series, intelligentTrailingStopConfigParams.getCurrentSellStopLimitPercentageBelowBreakEven(), breakEvenIndicator);
        ExitIndicator belowBreakEvenIndicator = ExitIndicator.createShortBuyLimitIndicator(series, intelligentTrailingStopConfigParams.getCurrentSellStopLimitPercentageAboveBreakEven(), breakEvenIndicator);
        Indicator<Num> minBelowBreakEvenIndicator = createMinBelowBreakEvenIndicator(series, intelligentTrailingStopConfigParams, breakEvenIndicator);

        return new IntelligentShortTrailIndicator(aboveBreakEvenIndicator, belowBreakEvenIndicator, minBelowBreakEvenIndicator, breakEvenIndicator);
    }

    private static Indicator<Num> createMinBelowBreakEvenIndicator(BarSeries series, IntelligentTrailingStopConfigParams intelligentTrailingStopConfigParams, ExitIndicator breakEvenIndicator) throws TradingApiException, ExchangeNetworkException {
        ExitIndicator limitIndicator = ExitIndicator.createShortBuyLimitIndicator(series, intelligentTrailingStopConfigParams.getCurrentSellStopLimitPercentageMinimumAboveBreakEven(), breakEvenIndicator);
        BigDecimal minimumBelowBreakEvenAsFactor = BigDecimal.ONE.add(intelligentTrailingStopConfigParams.getCurrentSellStopLimitPercentageMinimumAboveBreakEven());
        TransformIndicator minimalDistanceNeededToBreakEven = TransformIndicator.divide(breakEvenIndicator, minimumBelowBreakEvenAsFactor);
        return CombineIndicator.max(limitIndicator, minimalDistanceNeededToBreakEven);
    }
}
