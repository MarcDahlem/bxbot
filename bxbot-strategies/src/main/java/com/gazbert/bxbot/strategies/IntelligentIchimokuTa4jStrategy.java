package com.gazbert.bxbot.strategies;

import static com.gazbert.bxbot.trading.api.util.ta4j.MarketEnterType.LONG_POSITION;
import static com.gazbert.bxbot.trading.api.util.ta4j.MarketEnterType.SHORT_POSITION;

import com.gazbert.bxbot.strategies.helper.IntelligentEnterPriceCalculator;
import com.gazbert.bxbot.strategies.helper.IntelligentStateTracker;
import com.gazbert.bxbot.strategies.helper.IntelligentTrailIndicator;
import com.gazbert.bxbot.trading.api.util.ta4j.MarketEnterType;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.TradingApiException;
import com.gazbert.bxbot.trading.api.util.ta4j.*;
import java.util.Optional;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.Rule;
import org.ta4j.core.indicators.UnstableIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.ConstantIndicator;
import org.ta4j.core.indicators.ichimoku.IchimokuKijunSenIndicator;
import org.ta4j.core.indicators.ichimoku.IchimokuTenkanSenIndicator;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;
import org.ta4j.core.rules.OverIndicatorRule;
import org.ta4j.core.rules.UnderIndicatorRule;

import java.awt.*;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.Collection;
import java.util.LinkedList;

@Component("intelligentIchimokuTa4jStrategy")
// used to load the strategy using Spring bean injection
@Scope("prototype") // create always a new instance if it is injected
public class IntelligentIchimokuTa4jStrategy extends AbstractIntelligentStrategy {

    private static final DecimalFormat DECIMAL_FORMAT_PERCENTAGE = new DecimalFormat("#.#### %");

    private static final int ICHIMOKU_SHORT_SPAN = 9;
    private static final int ICHIMOKU_LONG_SPAN = 26;

    private IchimokuKijunSenIndicator baseLine;
    private IchimokuTenkanSenIndicator conversionLine;
    private ExitIndicator cloudFarthermostLineAtEntryPrice;
    private IchimokuLead1FutureIndicator lead1Future;
    private IchimokuLead2FutureIndicator lead2Future;
    private Rule entryRuleLong;
    private Rule cloudGreenInFuture;

    private IchimokuLaggingSpanIndicator laggingSpan;
    private IntelligentTrailingStopConfigParams intelligentTrailingStopConfigParams;
    private ClosePriceIndicator closePriceIndicator;
    private Rule laggingSpanAbovePastPrice;
    private UnstableIndicator delayedConversionLine;
    private UnderIndicatorRule cloudRedInFuture;
    private UnderIndicatorRule laggingSpanBelowPastPrice;
    private Rule entryRuleShort;


    @Override
    protected void botWillStartup(StrategyConfig config) throws TradingApiException, ExchangeNetworkException {
        initTa4jStrategy();
    }

    private void initTa4jStrategy() throws TradingApiException, ExchangeNetworkException {
        BarSeries series = priceTracker.getSeries();
        closePriceIndicator = new ClosePriceIndicator(series);


        conversionLine = new IchimokuTenkanSenIndicator(series, ICHIMOKU_SHORT_SPAN); //9
        baseLine = new IchimokuKijunSenIndicator(series, ICHIMOKU_LONG_SPAN); //26
        laggingSpan = new IchimokuLaggingSpanIndicator(closePriceIndicator);
        lead1Future = new IchimokuLead1FutureIndicator(conversionLine, baseLine); //26
        lead2Future = new IchimokuLead2FutureIndicator(series, 2 * ICHIMOKU_LONG_SPAN); // 52

        UnstableIndicator lead1Current = new UnstableIndicator(new DelayIndicator(lead1Future, ICHIMOKU_LONG_SPAN), ICHIMOKU_LONG_SPAN);
        UnstableIndicator lead2Current = new UnstableIndicator(new DelayIndicator(lead2Future, ICHIMOKU_LONG_SPAN), ICHIMOKU_LONG_SPAN);

        Indicator<Num> lead1Past = new UnstableIndicator(new DelayIndicator(lead1Future, 2 * ICHIMOKU_LONG_SPAN), 2 * ICHIMOKU_LONG_SPAN);
        Indicator<Num> lead2Past = new UnstableIndicator(new DelayIndicator(lead2Future, 2 * ICHIMOKU_LONG_SPAN), 2 * ICHIMOKU_LONG_SPAN);


        CombineIndicator currentCloudUpperLine = CombineIndicator.max(lead1Current, lead2Current);
        CombineIndicator currentCloudLowerLine = CombineIndicator.min(lead1Current, lead2Current);

        UnstableIndicator delayedMarketPrice = new UnstableIndicator(new DelayIndicator(closePriceIndicator, ICHIMOKU_LONG_SPAN), ICHIMOKU_LONG_SPAN);

        cloudGreenInFuture = new OverIndicatorRule(lead1Future, lead2Future);
        Rule conversionLineCrossesBaseLine = new CrossedUpIndicatorRule(conversionLine, baseLine);
        Rule conversionLineCrossOverCloud = new OverIndicatorRule(baseLine, currentCloudUpperLine).and(conversionLineCrossesBaseLine);
        laggingSpanAbovePastPrice = new OverIndicatorRule(laggingSpan, delayedMarketPrice);
        Rule priceAboveTheCloud = new OverIndicatorRule(closePriceIndicator, currentCloudUpperLine);
        Rule priceAboveConversionLine = new OverIndicatorRule(closePriceIndicator, conversionLine);

        delayedConversionLine = new UnstableIndicator(new DelayIndicator(conversionLine, ICHIMOKU_LONG_SPAN), ICHIMOKU_LONG_SPAN);
        Rule laggingSpanAbovePastConversionLine = new OverIndicatorRule(laggingSpan, delayedConversionLine);

        entryRuleLong = priceAboveTheCloud
                .and(cloudGreenInFuture)
                .and(conversionLineCrossesBaseLine)
                .and(laggingSpanAbovePastPrice)
                .and(priceAboveConversionLine)
                .and(laggingSpanAbovePastConversionLine)
        ;

        Rule priceBelowTheCloud = new UnderIndicatorRule(closePriceIndicator, currentCloudLowerLine);
        cloudRedInFuture = new UnderIndicatorRule(lead1Future, lead2Future);
        Rule conversionLineCrossesDownBaseLine = new CrossedDownIndicatorRule(conversionLine, baseLine);
        Rule conversionLineDownCrossUnderCloud = new UnderIndicatorRule(baseLine, currentCloudLowerLine).and(conversionLineCrossesDownBaseLine);
        laggingSpanBelowPastPrice = new UnderIndicatorRule(laggingSpan, delayedMarketPrice);
        Rule priceBelowConversionLine = new UnderIndicatorRule(closePriceIndicator, conversionLine);
        Rule laggingSpanBelowPastConversionLine = new UnderIndicatorRule(laggingSpan, delayedConversionLine);

        entryRuleShort = priceBelowTheCloud
                .and(cloudRedInFuture)
                .and(conversionLineCrossesDownBaseLine)
                .and(laggingSpanBelowPastPrice)
                .and(priceBelowConversionLine)
                .and(laggingSpanBelowPastConversionLine)
        ;


        cloudFarthermostLineAtEntryPrice = new ExitIndicator(series, stateTracker.getBreakEvenIndicator(),
                entryIndex -> enterType -> index -> {
                    if (enterType.equals(LONG_POSITION)) {
                        return new ConstantIndicator<>(series, currentCloudLowerLine.getValue(entryIndex));
                    } else {
                        return new ConstantIndicator<>(series, currentCloudUpperLine.getValue(entryIndex));
                    }
                });
    }

    @Override
    protected Collection<Ta4j2Chart.ChartIndicatorConfig> createStrategySpecificLiveChartIndicators() throws TradingApiException, ExchangeNetworkException {
        ExitIndicator binanceBreakEvenIndicator = ExitIndicator.createBreakEvenIndicator(priceTracker.getSeries(), stateTracker.getBreakEvenIndicator(), new BigDecimal("0.00075"), new BigDecimal("0.00075"));
        LinkedList<Ta4j2Chart.ChartIndicatorConfig> result = new LinkedList<>();
        result.add(new Ta4j2Chart.ChartIndicatorConfig(conversionLine, "conversion line", Ta4j2Chart.BUY_SHORT_LOOKBACK_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(baseLine, "base line", Ta4j2Chart.BUY_LONG_LOOKBACK_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(lead1Future, "kumo a future", Color.GREEN, ICHIMOKU_LONG_SPAN * -1));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(lead2Future, "kumo b future", Color.RED, ICHIMOKU_LONG_SPAN * -1));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(cloudFarthermostLineAtEntryPrice, "exit stop price", Ta4j2Chart.SELL_LIMIT_2_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(laggingSpan, "lagging span", Ta4j2Chart.SELL_LIMIT_3_COLOR, ICHIMOKU_LONG_SPAN));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(binanceBreakEvenIndicator, "binanceBreakEvenIndicator", Ta4j2Chart.BUY_TRIGGER_COLOR));
        return result;
    }

    @Override
    protected IntelligentStateTracker.OrderPriceCalculator createExitPriceCalculator(StrategyConfig config) throws TradingApiException, ExchangeNetworkException {

        return new IntelligentStateTracker.OrderPriceCalculator() {
            private IntelligentTrailIndicator intelligentTrailIndicator;
            private Rule laggingSpanLongEmergencyStopReached;
            private Rule laggingSpanShortEmergencyStopReached;
            private boolean initialized = false;

            @Override
            public BigDecimal calculate(MarketEnterType type) throws TradingApiException, ExchangeNetworkException, StrategyException {
                initExitRules();

                int currentIndex = priceTracker.getSeries().getEndIndex();
                int lastEntryIndex = stateTracker.getBreakEvenIndicator().getLastRecordedEntryIndex();
                int checkIndex = lastEntryIndex == currentIndex ? currentIndex : currentIndex - 1;

                if (type.equals(LONG_POSITION) && laggingSpanLongEmergencyStopReached.isSatisfied(checkIndex)) {
                    return (BigDecimal) closePriceIndicator.getValue(currentIndex).getDelegate();
                }

                if (type.equals(SHORT_POSITION) && laggingSpanShortEmergencyStopReached.isSatisfied(checkIndex)) {
                    return (BigDecimal) closePriceIndicator.getValue(currentIndex).getDelegate();
                }

                BigDecimal stopLossPrice = (BigDecimal) cloudFarthermostLineAtEntryPrice.getValue(currentIndex).getDelegate();
                if (stopLossPrice == null) { // no lower line available --> was a resume.
                    if (type.equals(SHORT_POSITION)) {
                        throw new IllegalStateException("IntelligentStopLoss not implemented for SHORT so far");
                    }
                    return (BigDecimal) intelligentTrailIndicator.getValue(currentIndex).getDelegate();
                }
                return stopLossPrice;
            }

            private void initExitRules() throws TradingApiException, ExchangeNetworkException {
                if (!initialized) {
                    laggingSpanLongEmergencyStopReached = new UnderIndicatorRule(laggingSpan, delayedConversionLine);
                    laggingSpanShortEmergencyStopReached = new OverIndicatorRule(laggingSpan, delayedConversionLine);

                    intelligentTrailIndicator = IntelligentTrailIndicator.createIntelligentTrailIndicator(priceTracker.getSeries(), intelligentTrailingStopConfigParams, stateTracker.getBreakEvenIndicator());
                    initialized = true;
                }
            }

            @Override
            public void logStatistics(MarketEnterType marketEnterType) throws TradingApiException, ExchangeNetworkException, StrategyException {
                initExitRules();
                int currentIndex = priceTracker.getSeries().getEndIndex();
                LOG.info(market.getName() +
                        "\n######### MOVED DOWN? #########\n" +
                        "* Current market price: " + priceTracker.getFormattedLast() +
                        "\n Break even: " + priceTracker.formatWithCounterCurrency((BigDecimal) stateTracker.getBreakEvenIndicator().getValue(currentIndex).getDelegate()) +
                        "\n market change to break even: " + formatAsPercentage((BigDecimal) getPercentageChange(closePriceIndicator.getValue(currentIndex), stateTracker.getBreakEvenIndicator().getValue(currentIndex)).getDelegate()) +
                        "\n* lagging span: " + priceTracker.formatWithCounterCurrency((BigDecimal) laggingSpan.getValue(currentIndex).getDelegate()) +
                        "\n* past conversionline (" + ICHIMOKU_LONG_SPAN + "): " + priceTracker.formatWithCounterCurrency((BigDecimal) delayedConversionLine.getValue(currentIndex).getDelegate()) +
                        "\n lagging span to past conversionline: " + formatAsPercentage((BigDecimal) getPercentageChange(laggingSpan.getValue(currentIndex), delayedConversionLine.getValue(currentIndex)).getDelegate()) +
                        "\n Stop loss: " + priceTracker.formatWithCounterCurrency((BigDecimal) cloudFarthermostLineAtEntryPrice.getValue(currentIndex).getDelegate()) +
                        "\n market change to stop loss: " + formatAsPercentage((BigDecimal) getPercentageChange(closePriceIndicator.getValue(currentIndex), cloudFarthermostLineAtEntryPrice.getValue(currentIndex)).getDelegate()) +
                        "\n###############################");
            }
        };
    }

    private static String formatAsPercentage(BigDecimal number) {
        if (number == null) {
            return "<NaN> %";
        }
        return DECIMAL_FORMAT_PERCENTAGE.format(number);
    }

    @Override
    protected IntelligentStateTracker.OrderPriceCalculator createEnterPriceCalculator(StrategyConfig config) {
        IntelligentStateTracker.OrderPriceCalculator result = new IntelligentEnterPriceCalculator(market, priceTracker, config);
        return result;
    }

    @Override
    protected IntelligentStateTracker.OnTradeSuccessfullyClosedListener createTradesObserver(StrategyConfig config) {
        if (intelligentTrailingStopConfigParams == null) {
            intelligentTrailingStopConfigParams = new IntelligentTrailingStopConfigParams(config);
        }
        return intelligentTrailingStopConfigParams;
    }

    @Override
    protected Optional<MarketEnterType> shouldEnterMarket() {
        int currentIndex = priceTracker.getSeries().getEndIndex();
        boolean isLongRuleSatisfied = entryRuleLong.isSatisfied(currentIndex - 1);
        boolean isShortRuleSatisfied = entryRuleShort.isSatisfied(currentIndex - 1);
        LOG.info(() -> market.getName() +
                "\n######### MOVED UP? #########\n" +
                "* Current market price: " + priceTracker.getFormattedLast() +
                "\n-------------LONG-------------" +
                "\n* cloud green In future: " + cloudGreenInFuture.isSatisfied(currentIndex) +
                "\n* lagging span above past price: " + this.laggingSpanAbovePastPrice.isSatisfied(currentIndex) +
                "\n* Place a LONG BUY order?: " + isLongRuleSatisfied +
                "\n------------SHORT------------" +
                "\n* cloud red In future: " + cloudRedInFuture.isSatisfied(currentIndex) +
                "\n* lagging span below past price: " + this.laggingSpanBelowPastPrice.isSatisfied(currentIndex) +
                "\n* Place a SHORT SELL order?: " + isShortRuleSatisfied +
                "\n-----------------------------" +
                "\n#############################");
        if (isLongRuleSatisfied) {
            return Optional.of(LONG_POSITION);
        }
        if (isShortRuleSatisfied && market.isMarginTradingEnabled()) {
            return Optional.of(SHORT_POSITION);
        }
        return Optional.empty();
    }

    private Num getPercentageChange(Num newPrice, Num priceToCompareAgainst) {
        return newPrice.minus(priceToCompareAgainst).dividedBy(priceToCompareAgainst);
    }

    @Override
    protected boolean shouldExitMarket() throws TradingApiException, ExchangeNetworkException {
        return true;
    }

    @Override
    protected Collection<? extends Ta4j2Chart.ChartIndicatorConfig> createStrategySpecificOverviewChartIndicators() throws TradingApiException, ExchangeNetworkException {
        Collection<Ta4j2Chart.ChartIndicatorConfig> indicators = createStrategySpecificLiveChartIndicators();
        return indicators;
    }

    @Override
    protected void botWillShutdown() {
    }
}
