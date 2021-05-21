package com.gazbert.bxbot.trading.api.util.ta4j;

import com.gazbert.bxbot.trading.api.util.ta4j.TradeBasedIndicator;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;

public class TrueWhileInMarketIndicator extends TradeBasedIndicator<Boolean> {
    public TrueWhileInMarketIndicator(BarSeries series, TradeBasedIndicator<?> tradeKnowingIndicator) {
        super(series, tradeKnowingIndicator);
    }

    @Override
    protected Boolean calculateNoLastTradeAvailable(int index) {
        return false;
    }

    @Override
    protected Boolean calculateLastTradeWasEnter(int enterIndex, MarketEnterType enterType, int index) {
        return true;
    }

    @Override
    protected Boolean calculateLastTradeWasExit(int exitIndex, int index) {
        return false;
    }
}
