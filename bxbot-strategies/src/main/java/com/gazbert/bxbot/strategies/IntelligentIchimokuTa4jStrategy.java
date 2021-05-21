package com.gazbert.bxbot.strategies;

import static com.gazbert.bxbot.trading.api.util.ta4j.MarketEnterType.LONG_POSITION;

import com.gazbert.bxbot.strategies.helper.IntelligentBuyPriceCalculator;
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
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");


    private static final int ICHIMOKU_SHORT_SPAN = 9;
    private static final int ICHIMOKU_LONG_SPAN = 26;

    private BigDecimal buyFee;
    private BigDecimal sellFee;
    private IchimokuKijunSenIndicator baseLine;
    private IchimokuTenkanSenIndicator conversionLine;
    private ExitIndicator cloudLowerLineAtBuyPrice;
    private IchimokuLead1FutureIndicator lead1Future;
    private IchimokuLead2FutureIndicator lead2Future;
    private Rule buyRule;
    private Rule cloudGreenInFuture;

    private IchimokuLaggingSpanIndicator laggingSpan;
    private IntelligentTrailingStopConfigParams intelligentTrailingStopConfigParams;
    private ClosePriceIndicator closePriceIndicator;
    private Rule laggingSpanAbovePastPrice;
    private UnstableIndicator delayedConversionLine;


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
        UnderIndicatorRule laggingSpanAbovePastConversionLine = new UnderIndicatorRule(laggingSpan, delayedConversionLine);

        buyRule = priceAboveTheCloud
                .and(cloudGreenInFuture)
                .and(conversionLineCrossOverCloud)
                .and(laggingSpanAbovePastPrice)
                .and(priceAboveConversionLine)
                .and(laggingSpanAbovePastConversionLine)
        ;


        cloudLowerLineAtBuyPrice = new ExitIndicator(series, stateTracker.getBreakEvenIndicator(), buyIndex -> enterType -> index -> new ConstantIndicator<>(series, currentCloudLowerLine.getValue(buyIndex)));
    }

    @Override
    protected Collection<Ta4j2Chart.ChartIndicatorConfig> createStrategySpecificLiveChartIndicators() throws TradingApiException, ExchangeNetworkException {
        ExitIndicator binanceBreakEvenIndicator = ExitIndicator.createBreakEvenIndicator(priceTracker.getSeries(), stateTracker.getBreakEvenIndicator(), new BigDecimal("0.00075"), new BigDecimal("0.00075"));
        LinkedList<Ta4j2Chart.ChartIndicatorConfig> result = new LinkedList<>();
        result.add(new Ta4j2Chart.ChartIndicatorConfig(conversionLine, "conversion line", Ta4j2Chart.BUY_SHORT_LOOKBACK_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(baseLine, "base line", Ta4j2Chart.BUY_LONG_LOOKBACK_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(lead1Future, "kumo a future", Color.GREEN, ICHIMOKU_LONG_SPAN * -1));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(lead2Future, "kumo b future", Color.RED, ICHIMOKU_LONG_SPAN * -1));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(cloudLowerLineAtBuyPrice, "sell stop price", Ta4j2Chart.SELL_LIMIT_2_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(laggingSpan, "lagging span", Ta4j2Chart.SELL_LIMIT_3_COLOR, ICHIMOKU_LONG_SPAN));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(binanceBreakEvenIndicator, "binanceBreakEvenIndicator", Ta4j2Chart.BUY_TRIGGER_COLOR));
        return result;
    }

    @Override
    protected IntelligentStateTracker.OrderPriceCalculator createExitPriceCalculator(StrategyConfig config) throws TradingApiException, ExchangeNetworkException {
        buyFee = tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(market.getId());
        sellFee = tradingApi.getPercentageOfSellOrderTakenForExchangeFee(market.getId());

        return new IntelligentStateTracker.OrderPriceCalculator() {
            private IntelligentTrailIndicator intelligentTrailIndicator;
            private Rule laggingSpanEmergencyStopReached;
            private boolean initialized = false;

            @Override
            public BigDecimal calculate() throws TradingApiException, ExchangeNetworkException, StrategyException {
                initSellRules();

                int currentIndex = priceTracker.getSeries().getEndIndex();

                if (laggingSpanEmergencyStopReached.isSatisfied(currentIndex - 1)) {
                    return (BigDecimal) closePriceIndicator.getValue(currentIndex).getDelegate();
                }

                BigDecimal stopLossPrice = (BigDecimal) cloudLowerLineAtBuyPrice.getValue(currentIndex).getDelegate();
                if (stopLossPrice == null) { // no lower line available --> was a resume.
                    return (BigDecimal) intelligentTrailIndicator.getValue(currentIndex).getDelegate();
                }
                return stopLossPrice;
            }

            private void initSellRules() throws TradingApiException, ExchangeNetworkException {
                if (!initialized) {
                    laggingSpanEmergencyStopReached = new UnderIndicatorRule(laggingSpan, delayedConversionLine);

                    intelligentTrailIndicator = IntelligentTrailIndicator.createIntelligentTrailIndicator(priceTracker.getSeries(), intelligentTrailingStopConfigParams, stateTracker.getBreakEvenIndicator());
                    initialized = true;
                }
            }

            @Override
            public void logStatistics() throws TradingApiException, ExchangeNetworkException, StrategyException {
                initSellRules();
                int currentIndex = priceTracker.getSeries().getEndIndex();
                LOG.info(market.getName() +
                        "\n######### MOVED DOWN? #########\n" +
                        "* Current market price: " + priceTracker.getFormattedLast() +
                        "\n Break even: " + priceTracker.formatWithCounterCurrency((BigDecimal) stateTracker.getBreakEvenIndicator().getValue(currentIndex).getDelegate()) +
                        "\n market change to break even: " + formatAsPercentage((BigDecimal) getPercentageChange(closePriceIndicator.getValue(currentIndex), stateTracker.getBreakEvenIndicator().getValue(currentIndex)).getDelegate()) +
                        "\n* lagging span: " + priceTracker.formatWithCounterCurrency((BigDecimal) laggingSpan.getValue(currentIndex).getDelegate()) +
                        "\n* past conversionline (" + ICHIMOKU_LONG_SPAN + "): " + priceTracker.formatWithCounterCurrency((BigDecimal) delayedConversionLine.getValue(currentIndex).getDelegate()) +
                        "\n lagging span to past conversionline: " + formatAsPercentage((BigDecimal) getPercentageChange(laggingSpan.getValue(currentIndex), delayedConversionLine.getValue(currentIndex)).getDelegate()) +
                        "\n Stop loss: " + priceTracker.formatWithCounterCurrency((BigDecimal) cloudLowerLineAtBuyPrice.getValue(currentIndex).getDelegate()) +
                        "\n market change to stop loss: " + formatAsPercentage((BigDecimal) getPercentageChange(closePriceIndicator.getValue(currentIndex), cloudLowerLineAtBuyPrice.getValue(currentIndex)).getDelegate()) +
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
        IntelligentStateTracker.OrderPriceCalculator result = new IntelligentBuyPriceCalculator(market, priceTracker, config);
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
        boolean result = buyRule.isSatisfied(currentIndex - 1);
        LOG.info(() -> {
            return market.getName() +
                    "\n######### MOVED UP? #########\n" +
                    "* Current market price: " + priceTracker.getFormattedLast() +
                    "\n* cloud green In future: " + cloudGreenInFuture.isSatisfied(currentIndex) +
                    "\n* lagging span above past price: " + this.laggingSpanAbovePastPrice.isSatisfied(currentIndex) +
                    "\n* Place a BUY order?: " + result +
                    "\n#############################";
        });
        return result ? Optional.of(LONG_POSITION) : Optional.empty();
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
