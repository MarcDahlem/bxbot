package com.gazbert.bxbot.strategies.helper;

import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.trading.api.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
import java.util.List;

public class IntelligentStateTracker {

    private static final Logger LOG = LogManager.getLogger();

    private static final BigDecimal MINIMAL_ACCOUNT_BALANCE_FOR_RESUME_SELL = new BigDecimal("0.00000002");

    private final TradingApi tradingApi;
    private final Market market;
    private final IntelligentPriceTracker priceTracker;

    private IntelligentStrategyState strategyState;
    private PlacedOrder currentBuyOrder;
    private PlacedOrder currentSellOrder;

    public IntelligentStateTracker(TradingApi tradingApi, Market market, IntelligentPriceTracker priceTracker) {
        this.tradingApi = tradingApi;
        this.market = market;
        this.priceTracker = priceTracker;
    }

    public IntelligentStrategyState getCurrentState() throws ExchangeNetworkException, TradingApiException, StrategyException {
        if (strategyState == null) {
            LOG.info(() -> market.getName() + " First time that the strategy has been called - get the initial strategy state.");
            computeInitialStrategyState();
            LOG.info(() -> market.getName() + " Initial strategy state computed: " + this.strategyState);
        }
        return strategyState;
    }

    private void computeInitialStrategyState() throws ExchangeNetworkException, TradingApiException, StrategyException {
        final List<OpenOrder> myOrders = tradingApi.getYourOpenOrders(market.getId());
        if (myOrders.isEmpty()) {
            LOG.info(() -> market.getName() + " No open orders found. Check available balance for the base currency, to know if a new sell order should be created.");
            final BigDecimal currentBaseCurrencyBalance = priceTracker.getAvailableBaseCurrencyBalance();
            if (currentBaseCurrencyBalance.compareTo(MINIMAL_ACCOUNT_BALANCE_FOR_RESUME_SELL) > 0) {
                LOG.info(() -> market.getName() + " Open balance in base currency found. Resume needed. Set current phase to SELL and use as BUY price the current market ask price");
                currentBuyOrder = new PlacedOrder("DUMMY_STRATEGY_RESUMED_BUY_ORDER_DUE_TO_OPEN_BALANCE", OrderType.BUY, currentBaseCurrencyBalance, priceTracker.getAsk());
                strategyState = IntelligentStrategyState.NEED_SELL;
                return;
            } else {
                LOG.info(market.getName() + " No significant open balance in base currency found (" + priceTracker.getFormattedBaseCurrencyBalance() + "). No resume needed. Set current phase to BUY.");
                strategyState = IntelligentStrategyState.NEED_BUY;
                return;
            }
        }
        if (myOrders.size() != 1) {
            String errorMsg = " More than one open order (" + myOrders.size() + " open orders) in the market. Impossible to resume strategy.";
            LOG.info(() -> market.getName() + errorMsg);
            throw new StrategyException(errorMsg);
        }

        OpenOrder currentOpenOrder = myOrders.get(0);
        LOG.info(() -> market.getName() + " Found an open order on the market: '" + currentOpenOrder + "'. Try to calculate the resuming state...");
        if (currentOpenOrder.getType() == OrderType.BUY) {
            LOG.info(() -> market.getName() + " The current order is a BUY order. Resume with waiting for BUY to be fulfilled");
            currentBuyOrder = new PlacedOrder(currentOpenOrder.getId(), currentOpenOrder.getType(), currentOpenOrder.getOriginalQuantity(), currentOpenOrder.getPrice());
            strategyState = IntelligentStrategyState.WAIT_FOR_BUY;
        } else {
            LOG.info(() -> market.getName() + " The current order is a SELL order. Resume with waiting for SELL to be fulfilled or changing SELL prices.");
            BigDecimal estimatedBuyPrice = priceTracker.getAsk().max(currentOpenOrder.getPrice());
            currentBuyOrder = new PlacedOrder("DUMMY_STRATEGY_RESUMED_BUY_ORDER_DUE_TO_OPEN_SELL_ORDER", OrderType.BUY, currentOpenOrder.getQuantity(), estimatedBuyPrice);
            currentSellOrder = new PlacedOrder(currentOpenOrder.getId(), currentOpenOrder.getType(), currentOpenOrder.getQuantity(), currentOpenOrder.getPrice());
            strategyState = IntelligentStrategyState.WAIT_FOR_SELL;
        }
    }

    public void updateStateTo(IntelligentStrategyState newState) {
        LOG.info(() -> market.getName() + " Update strategy state from '" + strategyState + "' to '" + newState + "'");
        strategyState = newState;
    }
}
