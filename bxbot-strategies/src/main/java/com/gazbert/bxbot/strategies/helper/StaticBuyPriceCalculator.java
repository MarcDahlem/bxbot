package com.gazbert.bxbot.strategies.helper;

import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.TradingApiException;

import java.math.BigDecimal;

public class StaticBuyPriceCalculator implements IntelligentStateTracker.OrderPriceCalculator {
    private final BigDecimal fixedPrice;

    public StaticBuyPriceCalculator(BigDecimal fixedPrice) {

        this.fixedPrice = fixedPrice;
    }

    @Override
    public BigDecimal calculate() {
        return fixedPrice;
    }
}
