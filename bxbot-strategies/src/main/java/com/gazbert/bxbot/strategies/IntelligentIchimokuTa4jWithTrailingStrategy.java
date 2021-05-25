package com.gazbert.bxbot.strategies;

import com.gazbert.bxbot.strategies.helper.IntelligentEnterPriceCalculator;
import com.gazbert.bxbot.strategies.helper.IntelligentShortTrailIndicator;
import com.gazbert.bxbot.strategies.helper.IntelligentStateTracker;
import com.gazbert.bxbot.strategies.helper.IntelligentTrailIndicator;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.TradingApiException;
import com.gazbert.bxbot.trading.api.util.ta4j.*;
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
import org.ta4j.core.rules.*;

import java.awt.*;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Optional;

import static com.gazbert.bxbot.trading.api.util.ta4j.MarketEnterType.LONG_POSITION;
import static com.gazbert.bxbot.trading.api.util.ta4j.MarketEnterType.SHORT_POSITION;

@Component("intelligentIchimokuTa4jWithTrailingStrategy")
// used to load the strategy using Spring bean injection
@Scope("prototype") // create always a new instance if it is injected
public class IntelligentIchimokuTa4jWithTrailingStrategy extends AbstractIntelligentStrategy {

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

        Rule crossTheCurrentCloudUpperUp = new CrossedUpIndicatorRule(closePriceIndicator, currentCloudUpperLine);
        Rule crossTheCurrentCloudUpperDown = new CrossedDownIndicatorRule(closePriceIndicator, currentCloudUpperLine);

        Rule crossTheCurrentCloudLowerUp = new CrossedUpIndicatorRule(closePriceIndicator, currentCloudLowerLine);
        Rule crossTheCurrentCloudLowerDown = new CrossedDownIndicatorRule(closePriceIndicator, currentCloudLowerLine);

        UnstableIndicator delayedMarketPrice = new UnstableIndicator(new DelayIndicator(closePriceIndicator, ICHIMOKU_LONG_SPAN), ICHIMOKU_LONG_SPAN);

        cloudGreenInFuture = new OverIndicatorRule(lead1Future, lead2Future);
        laggingSpanAbovePastPrice = new OverIndicatorRule(laggingSpan, delayedMarketPrice);
        Rule priceAboveTheCloud = new OverIndicatorRule(closePriceIndicator, currentCloudUpperLine);
        Rule priceAboveConversionLine = new OverIndicatorRule(closePriceIndicator, conversionLine);
        OverIndicatorRule conversionLineAboveBaseLine = new OverIndicatorRule(conversionLine, baseLine);
        Rule laggingSpanAbovePastCloud = new OverIndicatorRule(laggingSpan, lead1Past).and(new OverIndicatorRule(laggingSpan, lead2Past));

        delayedConversionLine = new UnstableIndicator(new DelayIndicator(conversionLine, ICHIMOKU_LONG_SPAN), ICHIMOKU_LONG_SPAN);
        Rule laggingSpanAbovePastConversionLine = new OverIndicatorRule(laggingSpan, delayedConversionLine);

        BooleanIndicatorRule trueInBuyPhases = new BooleanIndicatorRule(new TrueWhileInMarketIndicator(series, stateTracker.getBreakEvenIndicator()));

        Rule resetUpperCrossUpOn = crossTheCurrentCloudUpperDown.or(trueInBuyPhases);
        Rule resetLowerCrossUpOn = crossTheCurrentCloudLowerDown.or(trueInBuyPhases);


        CombineIndicator pastCloudUpperLine = CombineIndicator.max(lead1Past, lead2Past);
        Rule crossThePastCloudUpperUp = new CrossedUpIndicatorRule(laggingSpan, pastCloudUpperLine);
        Rule crossThePastCloudUpperDown = new CrossedDownIndicatorRule(laggingSpan, pastCloudUpperLine);
        Rule resetPastUpperCrossUpOn = crossThePastCloudUpperDown.or(trueInBuyPhases);

        StrictBeforeRule laggingSpanCrossedUpper = new StrictBeforeRule(series, crossThePastCloudUpperUp, laggingSpanAbovePastCloud, resetPastUpperCrossUpOn);

        Rule crossingIndipendentIchimokuSignals = priceAboveTheCloud
                .and(cloudGreenInFuture
                        .and(conversionLineAboveBaseLine)
                        .and(laggingSpanAbovePastCloud))
                .and(laggingSpanAbovePastPrice)
                .and(priceAboveConversionLine)
                .and(laggingSpanAbovePastConversionLine);

        StrictBeforeRule crossUpperAndIchimokuSignals = new StrictBeforeRule(series, crossTheCurrentCloudUpperUp, crossingIndipendentIchimokuSignals, resetUpperCrossUpOn);

        entryRuleLong = new StrictBeforeRule(series, crossTheCurrentCloudLowerUp, crossUpperAndIchimokuSignals, resetLowerCrossUpOn).and(laggingSpanCrossedUpper);

        Rule priceBelowTheCloud = new UnderIndicatorRule(closePriceIndicator, currentCloudLowerLine);
        cloudRedInFuture = new UnderIndicatorRule(lead1Future, lead2Future);
        laggingSpanBelowPastPrice = new UnderIndicatorRule(laggingSpan, delayedMarketPrice);
        Rule priceBelowConversionLine = new UnderIndicatorRule(closePriceIndicator, conversionLine);
        Rule laggingSpanBelowPastConversionLine = new UnderIndicatorRule(laggingSpan, delayedConversionLine);
        UnderIndicatorRule conversionLineBelowBaseLine = new UnderIndicatorRule(conversionLine, baseLine);
        Rule laggingSpanBelowPastCloud = new UnderIndicatorRule(laggingSpan, lead1Past).and(new UnderIndicatorRule(laggingSpan, lead2Past));

        Rule crossingIndipendentShortIchimokuSignals = priceBelowTheCloud
                .and(cloudRedInFuture
                        .and(conversionLineBelowBaseLine)
                        .and(laggingSpanBelowPastCloud))
                .and(laggingSpanBelowPastPrice)
                .and(priceBelowConversionLine)
                .and(laggingSpanBelowPastConversionLine);

        Rule resetLowerCrossDownOn = crossTheCurrentCloudLowerUp.or(trueInBuyPhases);
        Rule resetShortUpperCrossDownOn = crossTheCurrentCloudUpperUp.or(trueInBuyPhases);

        CombineIndicator pastCloudLowerLine = CombineIndicator.min(lead1Past, lead2Past);
        Rule crossThePastCloudLowerDown = new CrossedDownIndicatorRule(laggingSpan, pastCloudLowerLine);
        Rule crossThePastCloudLowerUp = new CrossedUpIndicatorRule(laggingSpan, pastCloudLowerLine);
        Rule resetPastLowerCrossDownOn = crossThePastCloudLowerUp.or(trueInBuyPhases);

        StrictBeforeRule laggingSpanCrossedLowerDown = new StrictBeforeRule(series, crossThePastCloudLowerDown, laggingSpanBelowPastCloud, resetPastLowerCrossDownOn);
        StrictBeforeRule crossLowerAndAndShortIchimokuSignals = new StrictBeforeRule(series, crossTheCurrentCloudLowerDown, crossingIndipendentShortIchimokuSignals, resetLowerCrossDownOn);
        entryRuleShort = new StrictBeforeRule(series, crossTheCurrentCloudUpperDown, crossLowerAndAndShortIchimokuSignals, resetShortUpperCrossDownOn).and(laggingSpanCrossedLowerDown);


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
        result.add(new Ta4j2Chart.ChartIndicatorConfig(conversionLine, "conversion line", Ta4j2Chart.SELL_LIMIT_1_COLOR));
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
            private IntelligentShortTrailIndicator intelligentShortTrailIndicator;
            private IntelligentTrailIndicator intelligentLongTrailIndicator;
            private Rule laggingSpanLongEmergencyStopReached;
            private Rule laggingSpanShortEmergencyStopReached;
            private boolean initialized = false;

            @Override
            public BigDecimal calculate(MarketEnterType type) throws TradingApiException, ExchangeNetworkException, StrategyException {
                initExitRules();

                int currentIndex = priceTracker.getSeries().getEndIndex();
                switch (type) {
                    case LONG_POSITION:
                        if(laggingSpanLongEmergencyStopReached.isSatisfied(currentIndex - 1)) {
                            return (BigDecimal) closePriceIndicator.getValue(currentIndex).getDelegate();
                        } else {
                            return (BigDecimal) intelligentLongTrailIndicator.getValue(currentIndex-1).getDelegate();
                        }
                    case SHORT_POSITION:
                        if(laggingSpanShortEmergencyStopReached.isSatisfied(currentIndex - 1)) {
                            return (BigDecimal) closePriceIndicator.getValue(currentIndex).getDelegate();
                        } else {
                            return (BigDecimal) intelligentShortTrailIndicator.getValue(currentIndex-1).getDelegate();
                        }
                    default:
                        throw new StrategyException("Unknown market entry type encountered: " + type);
                }
            }

            private void initExitRules() throws TradingApiException, ExchangeNetworkException {
                if (!initialized) {
                    laggingSpanLongEmergencyStopReached = new UnderIndicatorRule(laggingSpan, delayedConversionLine);
                    laggingSpanShortEmergencyStopReached = new OverIndicatorRule(laggingSpan, delayedConversionLine);

                    intelligentLongTrailIndicator = IntelligentTrailIndicator.createIntelligentTrailIndicator(priceTracker.getSeries(), intelligentTrailingStopConfigParams, stateTracker.getBreakEvenIndicator());
                    intelligentShortTrailIndicator = IntelligentShortTrailIndicator.createIntelligentShortTrailIndicator(priceTracker.getSeries(), intelligentTrailingStopConfigParams, stateTracker.getBreakEvenIndicator());
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
