package com.gazbert.bxbot.strategies;

import com.gazbert.bxbot.strategies.helper.IntelligentBuyPriceCalculator;
import com.gazbert.bxbot.strategies.helper.IntelligentSellPriceCalculator;
import com.gazbert.bxbot.strategies.helper.IntelligentStateTracker;
import com.gazbert.bxbot.strategies.helper.StaticBuyPriceCalculator;
import com.gazbert.bxbot.strategies.helper.StaticSellPriceParams;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
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
import com.gazbert.bxbot.trading.api.util.ta4j.TradeBasedIndicator;
import java.awt.*;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.Collection;
import java.util.LinkedList;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
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
import org.ta4j.core.rules.BooleanRule;
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

    private BaseStrategy ta4jStrategy;
    private BigDecimal buyFee;
    private BigDecimal sellFee;
    private IchimokuKijunSenIndicator baseLine;
    private IchimokuTenkanSenIndicator conversionLine;
    private IchimokuLaggingSpanIndicator laggingSpan;
    private SellIndicator cloudLowerLineAtBuyPrice;
    private Indicator<Num> gainSellPriceCalculator;
    private IchimokuLead1FutureIndicator lead1Future;
    private IchimokuLead2FutureIndicator lead2Future;

    @Override
    protected void botWillStartup(StrategyConfig config) throws TradingApiException, ExchangeNetworkException {
        initTa4jStrategy();
    }

    private void initTa4jStrategy() throws TradingApiException, ExchangeNetworkException {
        BarSeries series = priceTracker.getSeries();
        ClosePriceIndicator closePriceIndicator = new ClosePriceIndicator(series);
        LowPriceIndicator bidPriceIndicator = new LowPriceIndicator(series);
        HighPriceIndicator askPriceIndicator = new HighPriceIndicator(series);

        conversionLine = new IchimokuTenkanSenIndicator(series, ICHIMOKU_SHORT_SPAN); //9
        baseLine = new IchimokuKijunSenIndicator(series, ICHIMOKU_LONG_SPAN); //26
        laggingSpan = new IchimokuLaggingSpanIndicator(askPriceIndicator);
        lead1Future = new IchimokuLead1FutureIndicator(conversionLine, baseLine); //26
        lead2Future = new IchimokuLead2FutureIndicator(series, 2 * ICHIMOKU_LONG_SPAN); // 52

        UnstableIndicator lead1Current = new UnstableIndicator(new DelayIndicator(lead1Future, ICHIMOKU_LONG_SPAN), ICHIMOKU_LONG_SPAN);
        UnstableIndicator lead2Current = new UnstableIndicator(new DelayIndicator(lead2Future, ICHIMOKU_LONG_SPAN), ICHIMOKU_LONG_SPAN);

        Indicator<Num> lead1Past = new UnstableIndicator(new DelayIndicator(lead1Future, 2 * ICHIMOKU_LONG_SPAN), 2 * ICHIMOKU_LONG_SPAN);
        Indicator<Num> lead2Past = new UnstableIndicator(new DelayIndicator(lead2Future, 2 * ICHIMOKU_LONG_SPAN), 2 * ICHIMOKU_LONG_SPAN);


        CombineIndicator currentCloudUpperLine = CombineIndicator.max(lead1Current, lead2Current);
        Rule crossTheCurrentCloudUp = new CrossedUpIndicatorRule(askPriceIndicator, currentCloudUpperLine);
        Rule crossTheCurrentCloudDown = new CrossedDownIndicatorRule(askPriceIndicator, currentCloudUpperLine);

        Rule cloudGreenInFuture = new OverIndicatorRule(lead1Future, lead2Future);
        Rule conversionLineAboveBaseLine = new OverIndicatorRule(conversionLine, baseLine);
        Rule laggingSpanAbovePastCloud = new OverIndicatorRule(laggingSpan, lead1Past).and(new OverIndicatorRule(laggingSpan, lead2Past));

        Indicator<Boolean> trueInBuyPhases = new TrueInBuyPhaseIndicator(series, stateTracker.getBreakEvenIndicator());

        Rule resetCrossUpOn = crossTheCurrentCloudDown.or(new BooleanIndicatorRule(trueInBuyPhases));
        Rule entryRule = new StrictBeforeRule(series, crossTheCurrentCloudUp, cloudGreenInFuture.and(conversionLineAboveBaseLine).and(laggingSpanAbovePastCloud), resetCrossUpOn);


        CombineIndicator currentCloudLowerLine = CombineIndicator.min(lead1Current, lead2Current);

        cloudLowerLineAtBuyPrice = new SellIndicator(series, stateTracker.getBreakEvenIndicator(), (buyIndex, index) -> new ConstantIndicator<>(series, currentCloudLowerLine.getValue(buyIndex)));
        Number targetToRiskRatio = 2;
        Indicator<Num> buyPriceIndicator = new SellIndicator(series, stateTracker.getBreakEvenIndicator(), (buyIndex, index) -> new ConstantIndicator<>(series, askPriceIndicator.getValue(buyIndex)));

        CombineIndicator sellPriceGainCal = multiply(plus(multiply(minus(divide(bidPriceIndicator, cloudLowerLineAtBuyPrice), 1), targetToRiskRatio), 1), buyPriceIndicator);
        gainSellPriceCalculator = new SellIndicator(series, stateTracker.getBreakEvenIndicator(), (buyIndex, index) -> new ConstantIndicator<>(series, sellPriceGainCal.getValue(buyIndex)));
        Rule exitRule = new UnderIndicatorRule(bidPriceIndicator, cloudLowerLineAtBuyPrice).or(new OverIndicatorRule(bidPriceIndicator, gainSellPriceCalculator))
                .or(new UnderIndicatorRule(laggingSpan, new UnstableIndicator(new DelayIndicator(askPriceIndicator, ICHIMOKU_LONG_SPAN), ICHIMOKU_LONG_SPAN)))
                ;
        ta4jStrategy = new BaseStrategy("Intelligent Ta4j Ichimoku", entryRule, exitRule);
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
        result.add(new Ta4j2Chart.ChartIndicatorConfig(laggingSpan, "lagging span", Ta4j2Chart.SELL_CURRENT_LIMIT_COLOR, ICHIMOKU_LONG_SPAN));
        return result;
    }

    @Override
    protected IntelligentStateTracker.OrderPriceCalculator createSellPriceCalculator(StrategyConfig config) throws TradingApiException, ExchangeNetworkException {
        buyFee = tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(market.getId());
        sellFee = tradingApi.getPercentageOfSellOrderTakenForExchangeFee(market.getId());
        return new IntelligentSellPriceCalculator(priceTracker, stateTracker, new StaticSellPriceParams(buyFee, sellFee, config));
    }

    @Override
    protected IntelligentStateTracker.OrderPriceCalculator createBuyPriceCalculator(StrategyConfig config) {
        IntelligentStateTracker.OrderPriceCalculator result = new IntelligentBuyPriceCalculator(market, priceTracker, config);
        return result;
    }

    @Override
    protected IntelligentStateTracker.OnTradeSuccessfullyClosedListener createTradesObserver(StrategyConfig config) {
        return new IntelligentTradeTracker();
    }

    @Override
    protected boolean marketMovedUp() {
        boolean result = ta4jStrategy.shouldEnter(priceTracker.getSeries().getEndIndex());
        LOG.info(() -> {
            Num currentLongEma = baseLine.getValue(priceTracker.getSeries().getEndIndex());
            Num currentShortEma = conversionLine.getValue(priceTracker.getSeries().getEndIndex());
            return market.getName() +
                    "\n######### MOVED UP? #########\n" +
                    "* Current ask price: " + priceTracker.getFormattedAsk() +
                    "\n* Current long EMA value: " + priceTracker.formatWithCounterCurrency((BigDecimal) currentLongEma.getDelegate()) +
                    "\n* Current short EMA value: " + priceTracker.formatWithCounterCurrency((BigDecimal) currentShortEma.getDelegate()) +
                    "\n* Percentage EMA gain needed: " + DECIMAL_FORMAT_PERCENTAGE.format((BigDecimal) getPercentageChange(currentLongEma, currentShortEma).getDelegate()) +
                    "\n* Absolute EMA gain needed: " + priceTracker.formatWithCounterCurrency((BigDecimal) currentLongEma.minus(currentShortEma).getDelegate()) +
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
        boolean result = ta4jStrategy.shouldExit(priceTracker.getSeries().getEndIndex());
        Num currentLongEma = lead2Future.getValue(priceTracker.getSeries().getEndIndex());
        Num currentShortEma = lead1Future.getValue(priceTracker.getSeries().getEndIndex());
        LOG.info(market.getName() +
                "\n######### MOVED DOWN? #########\n" +
                "* Current bid price: " + priceTracker.getFormattedBid() +
                "\n Break even: " + priceTracker.formatWithCounterCurrency((BigDecimal) stateTracker.getBreakEvenIndicator().getValue(priceTracker.getSeries().getEndIndex()).getDelegate()) +
                "\n market change (bid) to break even: " + DECIMAL_FORMAT_PERCENTAGE.format((BigDecimal) getPercentageChange(priceTracker.getSeries().numOf(priceTracker.getBid()), stateTracker.getBreakEvenIndicator().getValue(priceTracker.getSeries().getEndIndex())).getDelegate()) +
                "\n* Current long EMA value: " + priceTracker.formatWithCounterCurrency((BigDecimal) currentLongEma.getDelegate()) +
                "\n* Current short EMA value: " + priceTracker.formatWithCounterCurrency((BigDecimal) currentShortEma.getDelegate()) +
                "\n* Percentage EMA loss needed: " + DECIMAL_FORMAT_PERCENTAGE.format((BigDecimal) getPercentageChange(currentLongEma, currentShortEma).getDelegate()) +
                "\n* Absolute EMA loss needed: " + priceTracker.formatWithCounterCurrency((BigDecimal) currentLongEma.minus(currentShortEma).getDelegate()) +
                "\n* Place a SELL order?: " + result +
                "\n###############################");
        return result;
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
