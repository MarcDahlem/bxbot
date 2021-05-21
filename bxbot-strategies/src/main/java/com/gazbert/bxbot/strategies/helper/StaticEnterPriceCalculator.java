package com.gazbert.bxbot.strategies.helper;

import com.gazbert.bxbot.trading.api.Market;
import com.gazbert.bxbot.trading.api.util.ta4j.MarketEnterType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
import java.text.DecimalFormat;

public class StaticEnterPriceCalculator extends AbstractEnterPriceCalculator {
    private final BigDecimal fixedPrice;
    private static final Logger LOG = LogManager.getLogger();
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat( "#.########");

    public StaticEnterPriceCalculator(Market market, IntelligentPriceTracker priceTracker, BigDecimal fixedPrice) {
        super(market, priceTracker);
        this.fixedPrice = fixedPrice;
    }

    @Override
    protected BigDecimal getBalanceToUseForEnterOrder(MarketEnterType type) {
        return priceTracker.getLast().multiply(fixedPrice);
    }
}
