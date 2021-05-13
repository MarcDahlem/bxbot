package com.gazbert.bxbot.strategies;

import com.gazbert.bxbot.strategies.helper.IntelligentBuyPriceCalculator;
import com.gazbert.bxbot.strategies.helper.IntelligentStateTracker;
import com.gazbert.bxbot.strategies.helper.IntelligentTrailIndicator;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.TradingApiException;
import com.gazbert.bxbot.trading.api.util.ta4j.CombineIndicator;
import com.gazbert.bxbot.trading.api.util.ta4j.DelayIndicator;
import com.gazbert.bxbot.trading.api.util.ta4j.IchimokuLaggingSpanIndicator;
import com.gazbert.bxbot.trading.api.util.ta4j.IchimokuLead1FutureIndicator;
import com.gazbert.bxbot.trading.api.util.ta4j.IchimokuLead2FutureIndicator;
import com.gazbert.bxbot.trading.api.util.ta4j.SellIndicator;
import com.gazbert.bxbot.trading.api.util.ta4j.StrictBeforeRule;
import com.gazbert.bxbot.trading.api.util.ta4j.Ta4j2Chart;
import com.gazbert.bxbot.trading.api.util.ta4j.TrueInBuyPhaseIndicator;
import java.awt.*;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.Collection;
import java.util.LinkedList;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.Rule;
import org.ta4j.core.indicators.UnstableIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.ConstantIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.indicators.ichimoku.IchimokuKijunSenIndicator;
import org.ta4j.core.indicators.ichimoku.IchimokuTenkanSenIndicator;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.BooleanIndicatorRule;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;
import org.ta4j.core.rules.OverIndicatorRule;
import org.ta4j.core.rules.UnderIndicatorRule;
import static com.gazbert.bxbot.trading.api.util.ta4j.CombineIndicator.divide;
import static com.gazbert.bxbot.trading.api.util.ta4j.CombineIndicator.multiply;
import static org.ta4j.core.indicators.helpers.TransformIndicator.minus;
import static org.ta4j.core.indicators.helpers.TransformIndicator.multiply;
import static org.ta4j.core.indicators.helpers.TransformIndicator.plus;

@Component("intelligentIchimokuTa4jStrategy") // used to load the strategy using Spring bean injection
@Scope("prototype") // create always a new instance if it is injected
public class IntelligentIchimokuTa4jStrategy extends AbstractIntelligentStrategy {

    private static final DecimalFormat DECIMAL_FORMAT_PERCENTAGE = new DecimalFormat("#.#### %");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");


    private static final int ICHIMOKU_SHORT_SPAN = 9*15;
    private static final int ICHIMOKU_LONG_SPAN = 26*15;

    private BigDecimal buyFee;
    private BigDecimal sellFee;
    private IchimokuKijunSenIndicator baseLine;
    private IchimokuTenkanSenIndicator conversionLine;
    private IchimokuLaggingSpanIndicator laggingSpanAsk;
    private SellIndicator cloudLowerLineAtBuyPrice;
    private Indicator<Num> gainSellPriceCalculator;
    private IchimokuLead1FutureIndicator lead1Future;
    private IchimokuLead2FutureIndicator lead2Future;
    private Rule buyRule;
    private Rule cloudGreenInFuture;
    private Rule conversionLineAboveBaseLine;
    private Rule laggingSpanAbovePastCloud;
    private HighPriceIndicator askPriceIndicator;
    private LowPriceIndicator bidPriceIndicator;
    private IchimokuLaggingSpanIndicator laggingSpanBid;
    private IntelligentTrailingStopConfigParams intelligentTrailingStopConfigParams;

    @Override
    protected void botWillStartup(StrategyConfig config) throws TradingApiException, ExchangeNetworkException {
        initTa4jStrategy();
    }

    private void initTa4jStrategy() throws TradingApiException, ExchangeNetworkException {
        BarSeries series = priceTracker.getSeries();
        ClosePriceIndicator closePriceIndicator = new ClosePriceIndicator(series);
        bidPriceIndicator = new LowPriceIndicator(series);
        askPriceIndicator = new HighPriceIndicator(series);

        conversionLine = new IchimokuTenkanSenIndicator(series, ICHIMOKU_SHORT_SPAN); //9
        baseLine = new IchimokuKijunSenIndicator(series, ICHIMOKU_LONG_SPAN); //26
        laggingSpanAsk = new IchimokuLaggingSpanIndicator(askPriceIndicator);
        laggingSpanBid = new IchimokuLaggingSpanIndicator(bidPriceIndicator);
        lead1Future = new IchimokuLead1FutureIndicator(conversionLine, baseLine); //26
        lead2Future = new IchimokuLead2FutureIndicator(series, 2 * ICHIMOKU_LONG_SPAN); // 52

        UnstableIndicator lead1Current = new UnstableIndicator(new DelayIndicator(lead1Future, ICHIMOKU_LONG_SPAN), ICHIMOKU_LONG_SPAN);
        UnstableIndicator lead2Current = new UnstableIndicator(new DelayIndicator(lead2Future, ICHIMOKU_LONG_SPAN), ICHIMOKU_LONG_SPAN);

        Indicator<Num> lead1Past = new UnstableIndicator(new DelayIndicator(lead1Future, 2 * ICHIMOKU_LONG_SPAN), 2 * ICHIMOKU_LONG_SPAN);
        Indicator<Num> lead2Past = new UnstableIndicator(new DelayIndicator(lead2Future, 2 * ICHIMOKU_LONG_SPAN), 2 * ICHIMOKU_LONG_SPAN);


        CombineIndicator currentCloudUpperLine = CombineIndicator.max(lead1Current, lead2Current);
        CombineIndicator currentCloudLowerLine = CombineIndicator.min(lead1Current, lead2Current);

        Rule crossTheCurrentCloudUpperUp = new CrossedUpIndicatorRule(askPriceIndicator, currentCloudUpperLine);
        Rule crossTheCurrentCloudUpperDown = new CrossedDownIndicatorRule(askPriceIndicator, currentCloudUpperLine);

        Rule crossTheCurrentCloudLowerUp = new CrossedUpIndicatorRule(askPriceIndicator, currentCloudLowerLine);
        Rule crossTheCurrentCloudLowerDown = new CrossedDownIndicatorRule(askPriceIndicator, currentCloudLowerLine);

        cloudGreenInFuture = new OverIndicatorRule(lead1Future, lead2Future);
        conversionLineAboveBaseLine = new OverIndicatorRule(conversionLine, baseLine);
        laggingSpanAbovePastCloud = new OverIndicatorRule(laggingSpanAsk, lead1Past).and(new OverIndicatorRule(laggingSpanAsk, lead2Past));

        BooleanIndicatorRule trueInBuyPhases = new BooleanIndicatorRule(new TrueInBuyPhaseIndicator(series, stateTracker.getBreakEvenIndicator()));

        Rule resetUpperCrossUpOn = crossTheCurrentCloudUpperDown.or(trueInBuyPhases);
        Rule resetLowerCrossUpOn = crossTheCurrentCloudLowerDown.or(trueInBuyPhases);

        Rule crossingIndipendentIchimokuSignals = cloudGreenInFuture.and(conversionLineAboveBaseLine).and(laggingSpanAbovePastCloud);
        StrictBeforeRule crossUpperAndIchimokuSignals = new StrictBeforeRule(series, crossTheCurrentCloudUpperUp, crossingIndipendentIchimokuSignals, resetUpperCrossUpOn);

        buyRule = new StrictBeforeRule(series, crossTheCurrentCloudLowerUp, crossUpperAndIchimokuSignals, resetLowerCrossUpOn);



        cloudLowerLineAtBuyPrice = new SellIndicator(series, stateTracker.getBreakEvenIndicator(), (buyIndex, index) -> new ConstantIndicator<>(series, currentCloudLowerLine.getValue(buyIndex)));
        Number targetToRiskRatio = 2;
        Indicator<Num> buyPriceIndicator = new SellIndicator(series, stateTracker.getBreakEvenIndicator(), (buyIndex, index) -> new ConstantIndicator<>(series, askPriceIndicator.getValue(buyIndex)));

        CombineIndicator sellPriceGainCal = multiply(plus(multiply(minus(divide(bidPriceIndicator, cloudLowerLineAtBuyPrice), 1), targetToRiskRatio), 1), buyPriceIndicator);
        gainSellPriceCalculator = new SellIndicator(series, stateTracker.getBreakEvenIndicator(), (buyIndex, index) -> new ConstantIndicator<>(series, sellPriceGainCal.getValue(buyIndex)));
    }

    @Override
    protected Collection<Ta4j2Chart.ChartIndicatorConfig> createStrategySpecificLiveChartIndicators() {
        LinkedList<Ta4j2Chart.ChartIndicatorConfig> result = new LinkedList<>();
        result.add(new Ta4j2Chart.ChartIndicatorConfig(conversionLine, "conversion line", Ta4j2Chart.BUY_SHORT_LOOKBACK_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(baseLine, "base line", Ta4j2Chart.BUY_LONG_LOOKBACK_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(lead1Future, "kumo a future", Color.GREEN, ICHIMOKU_LONG_SPAN * -1));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(lead2Future, "kumo b future", Color.RED, ICHIMOKU_LONG_SPAN * -1));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(cloudLowerLineAtBuyPrice, "sell stop price", Ta4j2Chart.SELL_LIMIT_2_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(gainSellPriceCalculator, "sell gain price", Ta4j2Chart.SELL_LIMIT_1_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(laggingSpanAsk, "lagging span (ask)", Ta4j2Chart.SELL_CURRENT_LIMIT_COLOR, ICHIMOKU_LONG_SPAN));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(laggingSpanBid, "lagging span (bid)", Ta4j2Chart.SELL_LIMIT_3_COLOR, ICHIMOKU_LONG_SPAN));
        return result;
    }

    @Override
    protected IntelligentStateTracker.OrderPriceCalculator createSellPriceCalculator(StrategyConfig config) throws TradingApiException, ExchangeNetworkException {
        buyFee = tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(market.getId());
        sellFee = tradingApi.getPercentageOfSellOrderTakenForExchangeFee(market.getId());

        return new IntelligentStateTracker.OrderPriceCalculator() {
            private IntelligentTrailIndicator intelligentTrailIndicator;
            private Indicator<Num> delayedBidPrice;
            private Rule laggingSpanEmergencyStopReached;
            private Rule takeProfitAndBreakEvenReached;
            private boolean initialized = false;

            @Override
            public BigDecimal calculate() throws TradingApiException, ExchangeNetworkException, StrategyException {
                initSellRules();

                int currentIndex = priceTracker.getSeries().getEndIndex();

                if(takeProfitAndBreakEvenReached.isSatisfied(currentIndex) || laggingSpanEmergencyStopReached.isSatisfied(currentIndex)) {
                    return (BigDecimal)  bidPriceIndicator.getValue(currentIndex).getDelegate();
                }

                BigDecimal stopLossPrice = (BigDecimal) cloudLowerLineAtBuyPrice.getValue(currentIndex).getDelegate();
                if (stopLossPrice == null) { // no lower line available --> was a resume.
                    return (BigDecimal) intelligentTrailIndicator.getValue(currentIndex).getDelegate();
                }
                return stopLossPrice;
            }

            private void initSellRules() throws TradingApiException, ExchangeNetworkException {
                if (!initialized) {
                    takeProfitAndBreakEvenReached = new OverIndicatorRule(bidPriceIndicator, gainSellPriceCalculator).and(new OverIndicatorRule(bidPriceIndicator, stateTracker.getBreakEvenIndicator()));
                    delayedBidPrice = new UnstableIndicator(new DelayIndicator(bidPriceIndicator, ICHIMOKU_LONG_SPAN), ICHIMOKU_LONG_SPAN);
                    laggingSpanEmergencyStopReached = new UnderIndicatorRule(laggingSpanBid, delayedBidPrice);
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
                        "* Current bid price: " + priceTracker.getFormattedBid() +
                        "\n Break even: " + priceTracker.formatWithCounterCurrency((BigDecimal) stateTracker.getBreakEvenIndicator().getValue(currentIndex).getDelegate()) +
                        "\n market change (bid) to break even: " + formatAsPercentage((BigDecimal) getPercentageChange(bidPriceIndicator.getValue(currentIndex), stateTracker.getBreakEvenIndicator().getValue(currentIndex)).getDelegate()) +
                        "\n Take profit: " + priceTracker.formatWithCounterCurrency((BigDecimal) gainSellPriceCalculator.getValue(currentIndex).getDelegate()) +
                        "\n market change (bid) to take profit: " + formatAsPercentage((BigDecimal) getPercentageChange(bidPriceIndicator.getValue(currentIndex), gainSellPriceCalculator.getValue(currentIndex)).getDelegate()) +
                        "\n* lagging span (bid): " + priceTracker.formatWithCounterCurrency((BigDecimal) laggingSpanBid.getValue(currentIndex).getDelegate()) +
                        "\n* past bid (" +ICHIMOKU_LONG_SPAN + "): " + priceTracker.formatWithCounterCurrency((BigDecimal) delayedBidPrice.getValue(currentIndex).getDelegate()) +
                        "\n lagging span (bid) to past bid: " + formatAsPercentage((BigDecimal) getPercentageChange(laggingSpanBid.getValue(currentIndex), delayedBidPrice.getValue(currentIndex)).getDelegate()) +
                        "\n Stop loss: " + priceTracker.formatWithCounterCurrency((BigDecimal) cloudLowerLineAtBuyPrice.getValue(currentIndex).getDelegate()) +
                        "\n market change (bid) to stop loss: " + formatAsPercentage((BigDecimal) getPercentageChange(bidPriceIndicator.getValue(currentIndex), cloudLowerLineAtBuyPrice.getValue(currentIndex)).getDelegate()) +
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
    protected IntelligentStateTracker.OrderPriceCalculator createBuyPriceCalculator(StrategyConfig config) {
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
    protected boolean marketMovedUp() {
        int currentIndex = priceTracker.getSeries().getEndIndex();
        boolean result = buyRule.isSatisfied(currentIndex);
        LOG.info(() -> {
            return market.getName() +
                    "\n######### MOVED UP? #########\n" +
                    "* Current ask price: " + priceTracker.getFormattedAsk() +
                    "\n* cloud green In future: " + cloudGreenInFuture.isSatisfied(currentIndex) +
                    "\n* conversion line above base line: " + this.conversionLineAboveBaseLine.isSatisfied(currentIndex) +
                    "\n* lagging span above past cloud: " + this.laggingSpanAbovePastCloud.isSatisfied(currentIndex) +
                    "\n* Place a BUY order?: " + result +
                    "\n#############################";
        });
        return result;
    }

    private Num getPercentageChange(Num newPrice, Num priceToCompareAgainst) {
        return newPrice.minus(priceToCompareAgainst).dividedBy(priceToCompareAgainst);
    }

    @Override
    protected boolean marketMovedDown() throws TradingApiException, ExchangeNetworkException {
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
