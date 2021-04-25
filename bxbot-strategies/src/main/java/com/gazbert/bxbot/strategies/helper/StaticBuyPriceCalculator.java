package com.gazbert.bxbot.strategies.helper;

import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.Market;
import com.gazbert.bxbot.trading.api.TradingApiException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
import java.text.DecimalFormat;

public class StaticBuyPriceCalculator extends AbstractBuyPriceCalculator {
    private final BigDecimal fixedPrice;
    private static final Logger LOG = LogManager.getLogger();
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat( "#.########");

    public StaticBuyPriceCalculator(Market market, IntelligentPriceTracker priceTracker, BigDecimal fixedPrice) {
        super(market, priceTracker);
        this.fixedPrice = fixedPrice;
    }

    @Override
    protected BigDecimal getBalanceToUseForBuyOrder() {
        return priceTracker.getAsk().multiply(fixedPrice);
    }
}
