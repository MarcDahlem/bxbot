package com.gazbert.bxbot.strategies;

import com.gazbert.bxbot.strategies.helper.*;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.TradingApiException;
import com.gazbert.bxbot.trading.api.util.ta4j.Ta4j2Chart;
import org.springframework.stereotype.Component;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowestValueIndicator;
import org.ta4j.core.indicators.helpers.TransformIndicator;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.OverIndicatorRule;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;

@Component("intelligentTa4jTrailingStopStrategy") // used to load the strategy using Spring bean injection
public class IntelligentTa4jTrailingStopStrategy extends AbstractIntelligentStrategy{

    private BigDecimal buyFee;
    private BigDecimal sellFee;
    private OverIndicatorRule buyRule;

    @Override
    protected void botWillStartup(StrategyConfig config) throws TradingApiException, ExchangeNetworkException {
        buyFee = tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(market.getId());
        sellFee = tradingApi.getPercentageOfSellOrderTakenForExchangeFee(market.getId());
        initTrailingStopRules(config);
    }

    private void initTrailingStopRules(StrategyConfig config) {
        BigDecimal configuredPercentageGainNeededToPlaceBuyOrder = StrategyConfigParser.readPercentageConfigValue(config, "initial-percentage-gain-needed-to-place-buy-order");
        int configuredLowestPriceLookbackCount = StrategyConfigParser.readInteger(config, "lowest-price-lookback-count");
        int configuredTimesAboveLowestPriceNeeded = StrategyConfigParser.readInteger(config, "times-above-lowest-price-needed");
        if (configuredTimesAboveLowestPriceNeeded > configuredLowestPriceLookbackCount) {
            throw new IllegalArgumentException("The amount for checking if the prices moved up must be lower or equal to the configured overall lookback");
        }

        HighPriceIndicator askPriceIndicator = new HighPriceIndicator(priceTracker.getSeries());
        LowestValueIndicator buyLongIndicator = new LowestValueIndicator(askPriceIndicator, configuredLowestPriceLookbackCount);
        LowestValueIndicator buyShortIndicator = new LowestValueIndicator(askPriceIndicator, configuredTimesAboveLowestPriceNeeded);
        Indicator<Num> buyGainLine = TransformIndicator.multiply(buyLongIndicator, BigDecimal.ONE.add(configuredPercentageGainNeededToPlaceBuyOrder));

        buyRule = new OverIndicatorRule(buyShortIndicator, buyGainLine);
    }

    @Override
    protected Collection<? extends Ta4j2Chart.ChartIndicatorConfig> createStrategySpecificLiveChartIndicators() throws TradingApiException, ExchangeNetworkException {
        return new HashSet<>();
    }

    @Override
    protected IntelligentStateTracker.OrderPriceCalculator createSellPriceCalculator(StrategyConfig config) {
        return new IntelligentSellPriceCalculator(priceTracker, stateTracker, new StaticSellPriceParams(buyFee, sellFee, config));
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
    protected boolean marketMovedUp() {
        return buyRule.isSatisfied(priceTracker.getSeries().getEndIndex());
    }

    @Override
    protected boolean marketMovedDown() throws TradingApiException, ExchangeNetworkException {
        return false;
    }

    @Override
    protected Collection<? extends Ta4j2Chart.ChartIndicatorConfig> createStrategySpecificOverviewChartIndicators() throws TradingApiException, ExchangeNetworkException {
        return createStrategySpecificLiveChartIndicators();
    }

    @Override
    protected void botWillShutdown() throws TradingApiException, ExchangeNetworkException {

    }
}
