package com.gazbert.bxbot.strategies;

import com.gazbert.bxbot.strategies.helper.IntelligentBuyPriceCalculator;
import com.gazbert.bxbot.strategies.helper.IntelligentStateTracker;
import com.gazbert.bxbot.strategies.helper.StaticBuyPriceCalculator;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.TradingApiException;
import com.gazbert.bxbot.trading.api.util.ta4j.Ta4j2Chart;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;

@Component("intelligentTa4jTrailingStopStrategy") // used to load the strategy using Spring bean injection
public class IntelligentTa4jTrailingStopStrategy extends AbstractIntelligentStrategy{

    @Override
    protected void botWillStartup() throws TradingApiException, ExchangeNetworkException {

    }

    @Override
    protected Collection<? extends Ta4j2Chart.ChartIndicatorConfig> createStrategySpecificLiveChartIndicators() throws TradingApiException, ExchangeNetworkException {
        return new HashSet<>();
    }

    @Override
    protected IntelligentStateTracker.OrderPriceCalculator createSellPriceCalculator(StrategyConfig config) {
        return null;
    }

    @Override
    protected IntelligentStateTracker.OrderPriceCalculator createBuyPriceCalculator(StrategyConfig config) {
        IntelligentStateTracker.OrderPriceCalculator result = new IntelligentBuyPriceCalculator(market, priceTracker, config);
        result = new StaticBuyPriceCalculator(market, priceTracker, new BigDecimal("25")); // TODO remove
        return result;
    }

    @Override
    protected IntelligentStateTracker.OnTradeSuccessfullyClosedListener createTradesObserver(StrategyConfig config) {
        return new IntelligentTradeTracker();
    }

    @Override
    protected boolean marketMovedUp() throws TradingApiException, ExchangeNetworkException {
        return false;
    }

    @Override
    protected boolean marketMovedDown() throws TradingApiException, ExchangeNetworkException {
        return false;
    }

    @Override
    protected Collection<? extends Ta4j2Chart.ChartIndicatorConfig> createStrategySpecificOverviewChartIndicators() throws TradingApiException, ExchangeNetworkException {
        return new HashSet<>();
    }

    @Override
    protected void botWillShutdown() throws TradingApiException, ExchangeNetworkException {

    }
}
