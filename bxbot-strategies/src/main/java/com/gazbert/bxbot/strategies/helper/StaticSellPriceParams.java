package com.gazbert.bxbot.strategies.helper;

import com.gazbert.bxbot.strategies.StrategyConfigParser;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.TradingApiException;

import java.math.BigDecimal;

public class StaticSellPriceParams implements IntelligentSellPriceCalculator.IntelligentSellPriceParameters {

    private final BigDecimal buyFee;
    private final BigDecimal sellFee;
    private final BigDecimal configuredSellStopLimitPercentageBelowBreakEven;
    private final BigDecimal configuredSellStopLimitPercentageAboveBreakEven;
    private final BigDecimal configuredSellStopLimitPercentageMinimumAboveBreakEven;

    public StaticSellPriceParams(BigDecimal buyFee, BigDecimal sellFee, StrategyConfig config) {
        this.buyFee = buyFee;
        this.sellFee = sellFee;

        this.configuredSellStopLimitPercentageBelowBreakEven = StrategyConfigParser.readPercentageConfigValue(config, "sell-stop-limit-percentage-below-break-even");
        this.configuredSellStopLimitPercentageAboveBreakEven = StrategyConfigParser.readPercentageConfigValue(config, "sell-stop-limit-percentage-above-break-even");
        this.configuredSellStopLimitPercentageMinimumAboveBreakEven = StrategyConfigParser.readPercentageConfigValue(config, "sell-stop-limit-percentage-minimum-above-break-even");
    }

    @Override
    public BigDecimal getBuyFee() {
        return buyFee;
    }

    @Override
    public BigDecimal getSellFee() {
        return sellFee;
    }

    @Override
    public BigDecimal getCurrentSellStopLimitPercentageBelowBreakEven() {
        return configuredSellStopLimitPercentageBelowBreakEven;
    }

    @Override
    public BigDecimal getCurrentSellStopLimitPercentageAboveBreakEven() {
        return configuredSellStopLimitPercentageAboveBreakEven;
    }

    @Override
    public BigDecimal getCurrentSellStopLimitPercentageMinimumAboveBreakEven() {
        return configuredSellStopLimitPercentageMinimumAboveBreakEven;
    }
}
