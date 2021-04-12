package com.gazbert.bxbot.strategies;

import com.gazbert.bxbot.trading.api.Market;
import com.gazbert.bxbot.trading.api.OrderType;
import com.gazbert.bxbot.trading.api.TradingApi;
import com.google.common.base.MoreObjects;

import java.math.BigDecimal;
import java.time.chrono.ThaiBuddhistChronology;

public class OrderState {

    private final String id;
    private final OrderType type;
    private final BigDecimal price;
    private final BigDecimal amount;
    private int orderNotExecutedCount;

    OrderState(String id, OrderType type, BigDecimal price, BigDecimal amount) {
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

    public BigDecimal getTotalIncludingFees(BigDecimal percentageOfFees) {
        BigDecimal total = getPrice().multiply(getAmount());
        BigDecimal fees = total.multiply(percentageOfFees);
        return total.add(fees);
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
}