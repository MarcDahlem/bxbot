package com.gazbert.bxbot.strategies.helper;

import com.gazbert.bxbot.trading.api.OpenOrder;

import java.math.BigDecimal;

public class OpenOrderState {
    private final OpenOrder orderInMarket;

    public OpenOrderState(OpenOrder order) {
        this.orderInMarket = order;
    }

    public boolean partiallyFilled() {
        return orderInMarket.getQuantity().compareTo(orderInMarket.getOriginalQuantity()) < 0;
    }

    public boolean isFullAvailable() {
        return orderInMarket.getQuantity().compareTo(orderInMarket.getOriginalQuantity()) == 0;
    }

    public BigDecimal getRemainingOrderAmount() {
        return orderInMarket.getQuantity();
    }

    public BigDecimal getExecutedOrderAmount() {
        return orderInMarket.getOriginalQuantity().subtract(getRemainingOrderAmount());
    }
}
