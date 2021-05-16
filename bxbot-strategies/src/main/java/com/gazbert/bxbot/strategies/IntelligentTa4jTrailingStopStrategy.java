package com.gazbert.bxbot.strategies;

import com.gazbert.bxbot.strategies.helper.IntelligentBuyPriceCalculator;
import com.gazbert.bxbot.strategies.helper.IntelligentStateTracker;
import com.gazbert.bxbot.strategies.helper.IntelligentTrailIndicator;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.TradingApiException;
import com.gazbert.bxbot.trading.api.util.ta4j.SellIndicator;
import com.gazbert.bxbot.trading.api.util.ta4j.Ta4j2Chart;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedList;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.TransformIndicator;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.OverIndicatorRule;
import org.ta4j.core.rules.UnderIndicatorRule;

@Component("intelligentTa4jTrailingStopStrategy")
// used to load the strategy using Spring bean injection
@Scope("prototype") // create always a new instance if it is injected
public class IntelligentTa4jTrailingStopStrategy extends AbstractIntelligentStrategy {

    private BigDecimal buyFee;
    private BigDecimal sellFee;
    private OverIndicatorRule buyRule;
    private IntelligentTrailingStopConfigParams intelligentTrailingStopConfigParams;
    private UnderIndicatorRule sellRule;
    private IntelligentTrailIndicator intelligentTrailIndicator;
    private SellIndicator aboveBreakEvenIndicator;
    private Indicator<Num> minAboveBreakEvenIndicator;
    private SellIndicator belowBreakEvenIndicator;
    private Indicator<Num> buyLongIndicator;
    //private Indicator<Num> buyShortIndicator;
    private TransformIndicator buyGainLine;

    @Override
    protected void botWillStartup(StrategyConfig config) throws TradingApiException, ExchangeNetworkException {
        initTrailingStopRules(config);
    }

    private void initTrailingStopRules(StrategyConfig config) throws TradingApiException, ExchangeNetworkException {
        initBuyRule();
        initSellIndicators();
    }

    private void initBuyRule() throws TradingApiException, ExchangeNetworkException {
        ClosePriceIndicator closePriceIndicator = new ClosePriceIndicator(priceTracker.getSeries());
        buyLongIndicator = SellIndicator.createLowestSinceLastSellIndicator(closePriceIndicator, intelligentTrailingStopConfigParams.getCurrentLowestPriceLookbackCount(), stateTracker.getBreakEvenIndicator());
        //buyShortIndicator = SellIndicator.createLowestSinceLastSellIndicator(closePriceIndicator, intelligentTrailingStopConfigParams.getCurrentTimesAboveLowestPriceNeeded(), stateTracker.getBreakEvenIndicator());
        buyGainLine = TransformIndicator.multiply(SellIndicator.createLowestSinceLastSellIndicator(buyLongIndicator, 400, stateTracker.getBreakEvenIndicator()), BigDecimal.ONE.add(intelligentTrailingStopConfigParams.getCurrentPercentageGainNeededForBuy()));
        //buyGainLine = TransformIndicator.multiply(buyLongIndicator, BigDecimal.ONE.add(intelligentTrailingStopConfigParams.getCurrentPercentageGainNeededForBuy()));

        buyRule = new OverIndicatorRule(closePriceIndicator, buyGainLine);
        //buyRule = new OverIndicatorRule(buyShortIndicator, buyGainLine);
    }

    private void initSellIndicators() throws TradingApiException, ExchangeNetworkException {
        intelligentTrailIndicator = IntelligentTrailIndicator.createIntelligentTrailIndicator(priceTracker.getSeries(), intelligentTrailingStopConfigParams, stateTracker.getBreakEvenIndicator());
        belowBreakEvenIndicator = intelligentTrailIndicator.getBelowBreakEvenIndicator();
        aboveBreakEvenIndicator = intelligentTrailIndicator.getAboveBreakEvenIndicator();
        minAboveBreakEvenIndicator = intelligentTrailIndicator.getMinAboveBreakEvenIndicator();

        ClosePriceIndicator closePriceIndicator = new ClosePriceIndicator(priceTracker.getSeries());
        sellRule = new UnderIndicatorRule(closePriceIndicator, intelligentTrailIndicator);
    }

    @Override
    protected Collection<? extends Ta4j2Chart.ChartIndicatorConfig> createStrategySpecificLiveChartIndicators() throws TradingApiException, ExchangeNetworkException {
        Collection<Ta4j2Chart.ChartIndicatorConfig> result = new LinkedList<>();
        result.add(new Ta4j2Chart.ChartIndicatorConfig(aboveBreakEvenIndicator, "limit above BE", Ta4j2Chart.SELL_LIMIT_2_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(minAboveBreakEvenIndicator, "limit min above BE", Ta4j2Chart.SELL_LIMIT_1_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(belowBreakEvenIndicator, "limit below BE", Ta4j2Chart.SELL_LIMIT_3_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(buyLongIndicator, "lowest (" + intelligentTrailingStopConfigParams.getCurrentLowestPriceLookbackCount() + ")", Ta4j2Chart.BUY_LONG_LOOKBACK_COLOR));
        //result.add(new Ta4j2Chart.ChartIndicatorConfig(buyShortIndicator, "lowest (" + intelligentTrailingStopConfigParams.getCurrentTimesAboveLowestPriceNeeded() + ")", Ta4j2Chart.TRAILING_BUY_SHORT_LOOKBACK_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(buyGainLine, "buy distance", Ta4j2Chart.BUY_TRIGGER_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(intelligentTrailIndicator, "intelligent trail stop loss", Ta4j2Chart.SELL_CURRENT_LIMIT_COLOR));
        return result;
    }

    @Override
    protected IntelligentStateTracker.OrderPriceCalculator createSellPriceCalculator(StrategyConfig config) throws TradingApiException, ExchangeNetworkException {
        buyFee = tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(market.getId());
        sellFee = tradingApi.getPercentageOfSellOrderTakenForExchangeFee(market.getId());
        return new IntelligentStateTracker.OrderPriceCalculator() {
            @Override
            public BigDecimal calculate() throws TradingApiException, ExchangeNetworkException, StrategyException {
                return (BigDecimal) intelligentTrailIndicator.getValue(priceTracker.getSeries().getEndIndex()).getDelegate();
            }

            @Override
            public void logStatistics() throws TradingApiException, ExchangeNetworkException, StrategyException {

            }
        };
    }

    @Override
    protected IntelligentStateTracker.OrderPriceCalculator createBuyPriceCalculator(StrategyConfig config) {
        IntelligentStateTracker.OrderPriceCalculator result = new IntelligentBuyPriceCalculator(market, priceTracker, config);
        //result = new StaticBuyPriceCalculator(market, priceTracker, new BigDecimal("25")); // TODO remove
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
        return buyRule.isSatisfied(priceTracker.getSeries().getEndIndex());
    }

    @Override
    protected boolean marketMovedDown() throws TradingApiException, ExchangeNetworkException {
        return true;
    }

    @Override
    protected Collection<? extends Ta4j2Chart.ChartIndicatorConfig> createStrategySpecificOverviewChartIndicators() throws TradingApiException, ExchangeNetworkException {
        return createStrategySpecificLiveChartIndicators();
    }

    @Override
    protected void botWillShutdown() throws TradingApiException, ExchangeNetworkException {

    }
}
