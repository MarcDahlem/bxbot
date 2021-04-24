package com.gazbert.bxbot.strategies.helper;

import com.gazbert.bxbot.strategies.StrategyConfigParser;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.trading.api.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.List;
import java.util.UUID;

import static com.gazbert.bxbot.strategies.helper.IntelligentStrategyState.*;

public class IntelligentStateTracker {

    private static final Logger LOG = LogManager.getLogger();
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat( "#.########");

    private static final BigDecimal MINIMAL_ACCOUNT_BALANCE_FOR_RESUME_SELL = new BigDecimal("0.00000002");

    private final TradingApi tradingApi;
    private final Market market;
    private final IntelligentPriceTracker priceTracker;
    private final boolean debugModeEnabled;

    private IntelligentStrategyState strategyState;
    private PlacedOrder currentBuyOrder;
    private PlacedOrder currentSellOrder;

    public IntelligentStateTracker(TradingApi tradingApi, Market market, IntelligentPriceTracker priceTracker, StrategyConfig config) {
        this.tradingApi = tradingApi;
        this.market = market;
        this.priceTracker = priceTracker;
        debugModeEnabled = StrategyConfigParser.readBoolean(config, "debug-mode-enabled", false);
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

    public BigDecimal getCurrentBuyOrderPrice() {
        return currentBuyOrder.getPrice();
    }

    public String getFormattedBuyOrderPrice() {
        return priceTracker.formatWithCounterCurrency(getCurrentBuyOrderPrice());
    }

    public BigDecimal getCurrentSellOrderPrice() {
        return currentSellOrder.getPrice();
    }

    public void trackRunningBuyOrder(OnStrategyStateChangeListener listener) throws TradingApiException, ExchangeNetworkException, StrategyException {
        if(strategyState != WAIT_FOR_BUY) {
            String errorMsg = "Invalid state encountered: " + strategyState + " while ask to track a running buy order. State needed: " + WAIT_FOR_BUY;
            LOG.error(() -> market.getName() + " " + errorMsg);
            throw new StrategyException(errorMsg);
        }
        OpenOrderState marketState = retrievOrderStateFromMarket(currentBuyOrder);

        switch (marketState) {
            case PARTIAL_AVAILABLE:
                if (getCurrentBuyOrderPrice().compareTo(priceTracker.getAsk()) > 0) {
                    LOG.warn(() -> market.getName() + " The order is partially filled but the current BUY order (price: '" + getFormattedBuyOrderPrice()
                            + ") is below the current market ask price (" + priceTracker.getFormattedAsk() + ").");
                } else {
                    LOG.warn(() -> market.getName() + " The order is partially filled but the market moved down (buy price: '" + getFormattedBuyOrderPrice()
                            + ", market ask price: " + priceTracker.getFormattedAsk() + ").");
                }
                currentBuyOrder.increaseOrderNotExecutedCounter();
                if (currentBuyOrder.getOrderNotExecutedCounter() <= 3) {
                    LOG.warn(() -> market.getName() + " The BUY order execution failed just '" + currentBuyOrder.getOrderNotExecutedCounter() + "' times so far. Wait a bit longer for the order to be processed.");
                } else {
                    LOG.warn(() -> market.getName() + " The BUY order execution failed '" + currentBuyOrder.getOrderNotExecutedCounter() + "' times. Waiting did not help. Cancel the rest of the order and proceed with selling the partially filled BUY order.");

                    boolean orderCanceled = true;
                    if (!debugModeEnabled){
                        orderCanceled = tradingApi.cancelOrder(currentBuyOrder.getId(), market.getId());
                    }

                    if (orderCanceled) {
                        LOG.info(() -> market.getName() + " Order '" + currentBuyOrder.getId() + "' successfully canceled. Compute executed order amount for the partial order.");
                        BigDecimal filledOrderAmount = priceTracker.getAvailableCounterCurrencyBalance();
                        currentBuyOrder = new PlacedOrder(currentBuyOrder.getId(), currentBuyOrder.getType(), filledOrderAmount, getCurrentBuyOrderPrice());
                        LOG.info(() -> market.getName() + " Replaced the order amount for '" + currentBuyOrder.getId() + "' successfully with '" + DECIMAL_FORMAT.format(filledOrderAmount) + "' according to the available funds on the account. Proceed with SELL phase");
                        updateStateTo(IntelligentStrategyState.NEED_SELL);
                        listener.onStrategyChanged(IntelligentStrategyState.NEED_SELL);
                    } else {
                        LOG.warn(() -> market.getName() + " Order '" + currentBuyOrder.getId() + "' canceling failed. Maybe it was fulfilled recently on the market. Wait another tick.");
                    }
                }
                break;
            case FULL_AVAILABLE:
                if (getCurrentBuyOrderPrice().compareTo(priceTracker.getAsk()) == 0) {
                    LOG.info(() -> market.getName() + " The current buy order's price '" + getFormattedBuyOrderPrice()
                            + "' is exactly on the current market ask-price ('" + priceTracker.getFormattedAsk() + "'). Wait another tick.");
                } else {
                    if (getCurrentBuyOrderPrice().compareTo(priceTracker.getAsk()) > 0) {
                        LOG.info(() -> market.getName() + " The current BUY order's price '" + getFormattedBuyOrderPrice()
                                + "' is above the current market ask-price ('" + priceTracker.getFormattedAsk() + "'). Cancel the order '" + currentBuyOrder.getId() + "'.");
                    } else {
                        currentBuyOrder.increaseOrderNotExecutedCounter();
                        if (currentBuyOrder.getOrderNotExecutedCounter() <= 3) {
                            LOG.warn(() -> market.getName() + " The BUY order execution failed just '" + currentBuyOrder.getOrderNotExecutedCounter() + "' times so far. Wait a bit longer for the order to be processed.");
                            return;
                        } else {
                            LOG.warn(() -> market.getName() + " The BUY order execution failed '" + currentBuyOrder.getOrderNotExecutedCounter() + "' times. Waiting did not help. Place a new BUY order to participate in the up trend.");
                        }
                    }
                    boolean orderCanceled = true;
                    if (!debugModeEnabled){
                        orderCanceled = tradingApi.cancelOrder(currentBuyOrder.getId(), market.getId());
                    }

                    if (orderCanceled) {
                        LOG.info(() -> market.getName() + " Order '" + currentBuyOrder.getId() + "' successfully canceled. Reset the strategy to the buy phase...");
                        currentBuyOrder = null;
                        updateStateTo(NEED_BUY);
                        listener.onStrategyChanged(NEED_BUY);
                    } else {
                        LOG.warn(() -> market.getName() + " Order '" + currentBuyOrder.getId() + "' canceling failed. Maybe it was fulfilled recently on the market. Wait another tick.");
                    }
                }
                break;
            case UNAVAILABLE:
                LOG.info(() -> market.getName() + " BUY order '" + currentBuyOrder.getId() + "' is not in the open orders anymore. Normally it was executed. Proceed to the sell phase...");
                updateStateTo(IntelligentStrategyState.NEED_SELL);
                listener.onStrategyChanged(NEED_SELL);
                break;
            default:
                throw new StrategyException("Unknown order market state encounted: " + marketState);
        }
    }

    public void trackRunningSellOrder(OrderPriceCalculator sellOrderPriceCalcuclator, OnStrategyStateChangeListener stateChangedListener, OnTradedSucesfullyClosedListener tradeClosedListener) throws TradingApiException, ExchangeNetworkException, StrategyException {
        if(strategyState != WAIT_FOR_SELL) {
            String errorMsg = "Invalid state encountered: " + strategyState + " while ask to track a running sell order. State needed: " + WAIT_FOR_SELL;
            LOG.error(() -> market.getName() + " " + errorMsg);
            throw new StrategyException(errorMsg);
        }
        BigDecimal currentMarketBidPrice = priceTracker.getBid();
        BigDecimal currentSellOrderPrice = getCurrentSellOrderPrice();
        OpenOrderState marketState = retrievOrderStateFromMarket(currentSellOrder);

        switch (marketState) {
            case PARTIAL_AVAILABLE:
            case FULL_AVAILABLE:

                LOG.info(() -> market.getName() + " SELL order '" + currentSellOrder.getId() + "' is still available. Check if the current bid price is below the order price.");
                if (currentSellOrderPrice.compareTo(currentMarketBidPrice) > 0) {
                    LOG.warn(() -> market.getName() + " The current SELL order is above the current market bid price. It should soon be fulfilled.");
                    currentSellOrder.increaseOrderNotExecutedCounter();
                    if (currentSellOrder.getOrderNotExecutedCounter() >= 10) { // TODO make 10 configurable or another approach
                        String errorMsg = market.getName() + " The current SELL order was 10 times above the current market price. It should normally be fulfilled. Stop the bot.";
                        LOG.error(() -> errorMsg);
                        throw new StrategyException(errorMsg);
                    }
                } else {
                    LOG.info(() -> market.getName() + " The current SELL order's price '" + priceTracker.formatWithCounterCurrency(currentSellOrderPrice)
                            + "' is below the current market bid price ('" + priceTracker.getFormattedBid() + "'). Check if the order must be updated and the stop limit must be increased");

                    BigDecimal sellPrice = sellOrderPriceCalcuclator.calculate();
                    if (sellPrice.compareTo(currentSellOrderPrice) > 0) {
                        LOG.info(() -> market.getName() + " The new SELL order's price '" + priceTracker.formatWithCounterCurrency(sellPrice)
                                + "' is higher than the the current sell order's price ('" + priceTracker.formatWithCounterCurrency(currentSellOrderPrice)+ "'). Cancel the current sell order '" + currentSellOrder.getId() + "' and trail the stop according to the higher stop limit.");

                        boolean orderCanceled = true;
                        if (!debugModeEnabled){
                            orderCanceled = tradingApi.cancelOrder(currentSellOrder.getId(), market.getId());
                        }

                        if (orderCanceled) {
                            LOG.info(() -> market.getName() + " Order '" + currentSellOrder.getId() + "' successfully canceled. Reset the strategy to the sell phase...");
                            currentSellOrder = null;
                            updateStateTo(IntelligentStrategyState.NEED_SELL);
                            stateChangedListener.onStrategyChanged(NEED_SELL);
                        } else {
                            LOG.warn(() -> market.getName() + " Order '" + currentBuyOrder.getId() + "' canceling failed. Maybe it was fulfilled recently on the market. Wait another tick.");
                        }
                    } else {
                        LOG.info(() -> market.getName() + " The new SELL order's price '" + priceTracker.formatWithCounterCurrency(sellPrice)
                                + "' is lower than the the current sell order's price ('" + priceTracker.formatWithCounterCurrency(currentSellOrderPrice) + "'). Wait for the order to fulfill or to increase trail in the next strategy tick.");
                    }
                }
                break;
            case UNAVAILABLE:
                LOG.info(() -> market.getName() + " SELL order '" + currentSellOrder.getId() + "' is not in the open orders anymore. Normally it was executed. Restart gaining money in the buy phase...");
                BigDecimal totalBuyPrice = getCurrentBuyOrderPrice().add(getCurrentBuyOrderPrice().multiply(tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(market.getId())));
                BigDecimal totalSellPrice = currentSellOrderPrice.subtract(currentSellOrderPrice.multiply(tradingApi.getPercentageOfSellOrderTakenForExchangeFee(market.getId())));
                BigDecimal totalGain = totalSellPrice.subtract(totalBuyPrice).multiply(currentSellOrder.getAmount());
                LOG.info(() -> market.getName() + " SELL order executed with a gain/loss of '" + priceTracker.formatWithCounterCurrency(totalGain) + "'. (sell order price: '" + priceTracker.formatWithCounterCurrency(currentSellOrderPrice) + "', sell order amount: '" + DECIMAL_FORMAT.format(currentSellOrder.getAmount()) + "')");
                tradeClosedListener.onTradeCloseSuccess(totalGain);
                currentBuyOrder = null;
                currentSellOrder = null;
                updateStateTo(NEED_BUY);
                stateChangedListener.onStrategyChanged(NEED_BUY);
                break;
            default:
                throw new StrategyException("Unknown order market state encounted: " + marketState);
        }


    }

    private OpenOrderState retrievOrderStateFromMarket(PlacedOrder order) throws ExchangeNetworkException, TradingApiException {
        final List<OpenOrder> myOrders = tradingApi.getYourOpenOrders(market.getId());
        for (final OpenOrder myOrder : myOrders) {
            if (myOrder.getId().equals(order.getId())) {
                LOG.info(() -> market.getName() + "Order is still available on the market: '. " + myOrder + "'. Check if it is fully available or partly fulfilled");
                if (myOrder.getQuantity().equals(myOrder.getOriginalQuantity())) {
                    LOG.info(() -> market.getName() + "Order completely available.");
                    return OpenOrderState.FULL_AVAILABLE;
                }
                LOG.info(() -> market.getName() + "Order partially executed.");
                return OpenOrderState.PARTIAL_AVAILABLE;
            }
        }
        return OpenOrderState.UNAVAILABLE;
    }

    public void placeBuyOrder(OrderPriceCalculator amountOfPiecesToBuyCalcualtor) throws TradingApiException, ExchangeNetworkException, StrategyException {
        if(strategyState != NEED_BUY) {
            String errorMsg = "Invalid state encountered: " + strategyState + " while ask to place a buy order. State needed: " + NEED_BUY;
            LOG.error(() -> market.getName() + " " + errorMsg);
            throw new StrategyException(errorMsg);
        }
        if (currentBuyOrder != null) {
            String errorMsg = "The BUY phase is to be executed, but there is still an open order '" + currentBuyOrder + "'. This should never happen. Stop the bot!";
            LOG.error(() -> errorMsg);
            throw new StrategyException(errorMsg);
        }

        final BigDecimal piecesToBuy = amountOfPiecesToBuyCalcualtor.calculate();

        LOG.info(() -> market.getName() + " BUY phase - Place a BUY order of '" + DECIMAL_FORMAT.format(piecesToBuy) + " * " + priceTracker.getFormattedAsk() + "'");
        String orderID;
        if (debugModeEnabled) {
            orderID = "DUMMY_BUY_ORDER_ID_" + UUID.randomUUID().toString();
        } else {
            orderID = tradingApi.createOrder(market.getId(), OrderType.BUY, piecesToBuy, priceTracker.getAsk());
        }

        LOG.info(() -> market.getName() + " BUY Order sent successfully to exchange. ID: " + orderID);

        currentBuyOrder = new PlacedOrder(orderID, OrderType.BUY, piecesToBuy, priceTracker.getAsk());
        updateStateTo(IntelligentStrategyState.WAIT_FOR_BUY);
    }

    public void placeSellOrder(OrderPriceCalculator sellPriceCalculator) throws TradingApiException, ExchangeNetworkException, StrategyException {
        if(strategyState != NEED_SELL) {
            String errorMsg = "Invalid state encountered: " + strategyState + " while ask to place a sell order. State needed: " + NEED_SELL;
            LOG.error(() -> market.getName() + " " + errorMsg);
            throw new StrategyException(errorMsg);
        }
        if (currentSellOrder != null) {
            String errorMsg = "The SELL phase is to be executed, but there is still an open order '" + currentSellOrder + "'. This should never happen. Stop the bot!";
            LOG.error(() -> errorMsg);
            throw new StrategyException(errorMsg);
        }

        BigDecimal sellPrice = sellPriceCalculator.calculate();
        LOG.info(() -> market.getName() + " SELL phase - Place a SELL order of '" + DECIMAL_FORMAT.format(currentBuyOrder.getAmount()) + " * " + priceTracker.formatWithCounterCurrency(sellPrice) + "'");

        String orderId;

        if (debugModeEnabled) {
            orderId = "DUMMY_SELL_ORDER_ID_" + UUID.randomUUID().toString();
        } else {
            orderId = tradingApi.createOrder(market.getId(), OrderType.SELL, currentBuyOrder.getAmount(), sellPrice);
        }

        LOG.info(() -> market.getName() + " SELL Order sent successfully to exchange. ID: " + orderId);

        currentSellOrder = new PlacedOrder(orderId, OrderType.SELL, currentBuyOrder.getAmount(), sellPrice);
        updateStateTo(IntelligentStrategyState.WAIT_FOR_SELL);
    }

    public interface OnStrategyStateChangeListener {
        void onStrategyChanged(IntelligentStrategyState newState) throws TradingApiException, ExchangeNetworkException, StrategyException;
    }

    public interface OnTradedSucesfullyClosedListener {
        void onTradeCloseSuccess(BigDecimal profit);
    }

    public interface OrderPriceCalculator {
        BigDecimal calculate() throws TradingApiException, ExchangeNetworkException, StrategyException;
    }
}
