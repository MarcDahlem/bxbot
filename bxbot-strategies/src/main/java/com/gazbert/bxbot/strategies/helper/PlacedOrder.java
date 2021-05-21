package com.gazbert.bxbot.strategies.helper;

import com.gazbert.bxbot.trading.api.Market;
import com.gazbert.bxbot.trading.api.OrderType;
import com.gazbert.bxbot.trading.api.TradingApi;
import com.gazbert.bxbot.trading.api.util.ta4j.MarketEnterType;
import com.google.common.base.MoreObjects;

import java.math.BigDecimal;
import java.time.chrono.ThaiBuddhistChronology;

public class PlacedOrder {

    private final String id;
    private final OrderType type;
    private final BigDecimal price;
    private final BigDecimal amount;
    private int orderNotExecutedCount;

    public PlacedOrder(String id, OrderType type, BigDecimal amount, BigDecimal price) {
        this.id = id;
        this.type = type;
        this.price = price;
        this.amount = amount;
    }

    public String getId() {
        return id;
    }

    public OrderType getType() {
        return type;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", getId())
                .add("type", getType())
                .add("price", getPrice())
                .add("amount", getAmount())
                .toString();
    }

    public void increaseOrderNotExecutedCounter() {
        this.orderNotExecutedCount++;
    }

    public int getOrderNotExecutedCounter() {
        return orderNotExecutedCount;
    }

    public MarketEnterType getMarketEnterType() {
        // TODO cleaner with subclassiung? Should only be called for ENTER orders
        switch (getType()) {
            case BUY:
                return MarketEnterType.LONG_POSITION;
            case SELL:
                return MarketEnterType.SHORT_POSITION;
            default:
                throw new IllegalStateException("Unknown order type " + getType());
        }
    }
}