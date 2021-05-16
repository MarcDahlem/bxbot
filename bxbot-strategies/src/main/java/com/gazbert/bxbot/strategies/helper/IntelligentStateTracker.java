package com.gazbert.bxbot.strategies.helper;

import com.gazbert.bxbot.strategies.StrategyConfigParser;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.trading.api.*;
import com.gazbert.bxbot.trading.api.util.ta4j.RecordedStrategy;
import com.gazbert.bxbot.trading.api.util.ta4j.SellIndicator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

import static com.gazbert.bxbot.strategies.helper.IntelligentStrategyState.*;

public class IntelligentStateTracker {

    private static final Logger LOG = LogManager.getLogger();
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat( "#.########");

    private final TradingApi tradingApi;
    private final Market market;
    private final IntelligentPriceTracker priceTracker;
    private final BigDecimal configuredEmergencyStop;

    private IntelligentStrategyState strategyState;
    private PlacedOrder currentBuyOrder;
    private PlacedOrder currentSellOrder;
    private SellIndicator breakEvenIndicator;

    private final static Map<Long, List<OpenOrder>> allOpenOrders = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(final Map.Entry eldest) {
            return size() > 10;
        }
    };

    public IntelligentStateTracker(TradingApi tradingApi, Market market, IntelligentPriceTracker priceTracker, StrategyConfig config) {
        this.tradingApi = tradingApi;
        this.market = market;
        this.priceTracker = priceTracker;
        configuredEmergencyStop = StrategyConfigParser.readAmount(config, "configured-emergency-stop-balance");
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
        final List<OpenOrder> myOrders = getAllOpenOrdersForMarket(market.getId());
        if (myOrders.isEmpty()) {
            LOG.info(() -> market.getName() + " No open orders found. Check available balance for the base currency, to know if a new sell order should be created.");
            final BigDecimal currentBaseCurrencyBalance = priceTracker.getAvailableBaseCurrencyBalance();
            if (currentBaseCurrencyBalance.compareTo(tradingApi.getMinimumOrderVolume(market.getId())) > 0) {
                LOG.info(() -> market.getName() + " Open balance in base currency found. Resume needed. Set current phase to SELL and use as BUY price the current market price");
                currentBuyOrder = new PlacedOrder("DUMMY_STRATEGY_RESUMED_BUY_ORDER_DUE_TO_OPEN_BALANCE", OrderType.BUY, currentBaseCurrencyBalance, priceTracker.getLast());
                strategyState = IntelligentStrategyState.NEED_SELL;
                getBreakEvenIndicator().registerBuyOrderExecution(priceTracker.getSeries().getEndIndex());
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
            BigDecimal estimatedBuyPrice = priceTracker.getLast().max(currentOpenOrder.getPrice());
            currentBuyOrder = new PlacedOrder("DUMMY_STRATEGY_RESUMED_BUY_ORDER_DUE_TO_OPEN_SELL_ORDER", OrderType.BUY, currentOpenOrder.getQuantity(), estimatedBuyPrice);
            currentSellOrder = new PlacedOrder(currentOpenOrder.getId(), currentOpenOrder.getType(), currentOpenOrder.getQuantity(), currentOpenOrder.getPrice());
            getBreakEvenIndicator().registerBuyOrderExecution(priceTracker.getSeries().getEndIndex());
            strategyState = IntelligentStrategyState.WAIT_FOR_SELL;
        }
    }

    private List<OpenOrder> getAllOpenOrdersForMarket(String id) throws ExchangeNetworkException, TradingApiException {
        long currentStrategyTick = priceTracker.getCurrentStrategyTick();
        if(!allOpenOrders.containsKey(currentStrategyTick)) {
            List<OpenOrder> allLoadedOrders = tradingApi.getYourOpenOrders(market.getId());
            allOpenOrders.put(currentStrategyTick, allLoadedOrders);
        }

        List<OpenOrder> currentOpenOrders = allOpenOrders.get(currentStrategyTick);
        return currentOpenOrders.stream().filter(openOrder -> market.getId().equalsIgnoreCase(openOrder.getMarketId())).collect(Collectors.toList());
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
                if (getCurrentBuyOrderPrice().compareTo(priceTracker.getLast()) > 0) {
                    LOG.warn(() -> market.getName() + " The order is partially filled but the current BUY order (price: '" + getFormattedBuyOrderPrice()
                            + ") is below the current market price (" + priceTracker.getFormattedLast() + ").");
                } else {
                    LOG.warn(() -> market.getName() + " The order is partially filled but the market moved down (buy price: '" + getFormattedBuyOrderPrice()
                            + ", market price: " + priceTracker.getFormattedLast() + ").");
                }
                currentBuyOrder.increaseOrderNotExecutedCounter();
                if (currentBuyOrder.getOrderNotExecutedCounter() <= 3) { // TODO make configurable + TODO reset counter
                    LOG.warn(() -> market.getName() + " The BUY order execution failed just '" + currentBuyOrder.getOrderNotExecutedCounter() + "' times so far. Wait a bit longer for the order to be processed.");
                } else {
                    LOG.warn(() -> market.getName() + " The BUY order execution failed '" + currentBuyOrder.getOrderNotExecutedCounter() + "' times. Waiting did not help. Cancel the rest of the order and proceed with selling the partially filled BUY order.");

                    boolean orderCanceled = tradingApi.cancelOrder(currentBuyOrder.getId(), market.getId());

                    if (orderCanceled) {
                        LOG.info(() -> market.getName() + " Order '" + currentBuyOrder.getId() + "' successfully canceled. Compute executed order amount for the partial order.");
                        BigDecimal filledOrderAmount = priceTracker.getAvailableBaseCurrencyBalance();
                        currentBuyOrder = new PlacedOrder(currentBuyOrder.getId(), currentBuyOrder.getType(), filledOrderAmount, getCurrentBuyOrderPrice());
                        LOG.info(() -> market.getName() + " Replaced the order amount for '" + currentBuyOrder.getId() + "' successfully with '" + DECIMAL_FORMAT.format(filledOrderAmount) + "' according to the available funds on the account. Proceed with SELL phase");
                        getBreakEvenIndicator().registerBuyOrderExecution(priceTracker.getSeries().getEndIndex());
                        updateStateTo(IntelligentStrategyState.NEED_SELL);
                        listener.onStrategyChanged(IntelligentStrategyState.NEED_SELL);
                    } else {
                        LOG.warn(() -> market.getName() + " Order '" + currentBuyOrder.getId() + "' canceling failed. Maybe it was fulfilled recently on the market. Wait another tick.");
                    }
                }
                break;
            case FULL_AVAILABLE:
                if (getCurrentBuyOrderPrice().compareTo(priceTracker.getLast()) == 0) {
                    LOG.info(() -> market.getName() + " The current buy order's price '" + getFormattedBuyOrderPrice()
                            + "' is exactly on the current market price ('" + priceTracker.getFormattedLast() + "'). Wait another tick.");
                } else {
                    if (getCurrentBuyOrderPrice().compareTo(priceTracker.getLast()) > 0) {
                        LOG.info(() -> market.getName() + " The current BUY order's price '" + getFormattedBuyOrderPrice()
                                + "' is above the current market price ('" + priceTracker.getFormattedLast() + "'). Cancel the order '" + currentBuyOrder.getId() + "'.");
                    } else {
                        currentBuyOrder.increaseOrderNotExecutedCounter();
                        if (currentBuyOrder.getOrderNotExecutedCounter() <= 3) { // TODO make configurable + TODO reset counter
                            LOG.warn(() -> market.getName() + " The BUY order execution failed just '" + currentBuyOrder.getOrderNotExecutedCounter() + "' times so far. Wait a bit longer for the order to be processed.");
                            return;
                        } else {
                            LOG.warn(() -> market.getName() + " The BUY order execution failed '" + currentBuyOrder.getOrderNotExecutedCounter() + "' times. Waiting did not help. Place a new BUY order to participate in the up trend.");
                        }
                    }
                    boolean orderCanceled = tradingApi.cancelOrder(currentBuyOrder.getId(), market.getId());

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
                getBreakEvenIndicator().registerBuyOrderExecution(priceTracker.getSeries().getEndIndex());
                updateStateTo(IntelligentStrategyState.NEED_SELL);
                listener.onStrategyChanged(NEED_SELL);
                break;
            default:
                throw new StrategyException("Unknown order market state encounted: " + marketState);
        }
    }

    public void trackRunningSellOrder(OrderPriceCalculator sellOrderPriceCalcuclator, OnStrategyStateChangeListener stateChangedListener, OnTradeSuccessfullyClosedListener tradeClosedListener) throws TradingApiException, ExchangeNetworkException, StrategyException {
        if(strategyState != WAIT_FOR_SELL) {
            String errorMsg = "Invalid state encountered: " + strategyState + " while ask to track a running sell order. State needed: " + WAIT_FOR_SELL;
            LOG.error(() -> market.getName() + " " + errorMsg);
            throw new StrategyException(errorMsg);
        }
        BigDecimal currentMarketPrice = priceTracker.getLast();
        BigDecimal currentSellOrderPrice = getCurrentSellOrderPrice();
        OpenOrderState marketState = retrievOrderStateFromMarket(currentSellOrder);

        switch (marketState) {
            case PARTIAL_AVAILABLE:
            case FULL_AVAILABLE:

                LOG.info(() -> market.getName() + " SELL order '" + currentSellOrder.getId() + "' is still available. Check if the current market price is below the order price.");
                if (currentSellOrderPrice.compareTo(currentMarketPrice) > 0) {
                    LOG.warn(() -> market.getName() + " The current SELL order is above the current market price. It should soon be fulfilled.");
                    currentSellOrder.increaseOrderNotExecutedCounter();
                    if (currentSellOrder.getOrderNotExecutedCounter() >= 10) { // TODO make 10 configurable or another approach
                        String msg = market.getName() + " The current SELL order was "+currentSellOrder.getOrderNotExecutedCounter()+" times above the current market price. It should normally be fulfilled. Cancel the order and place directly a sell order with the current markets price.";
                        LOG.warn(() -> msg);
                        BigDecimal availableBaseCurrencyBalance = priceTracker.getAvailableBaseCurrencyBalance();
                        BigDecimal minimumOrderVolume = tradingApi.getMinimumOrderVolume(market.getId());
                        if (minimumOrderVolume.compareTo(availableBaseCurrencyBalance) > 0 ) {
                            String minMsg = market.getName() + " The current SELL order was partially filled. The remaining volume '"+priceTracker.getFormattedBaseCurrencyBalance()+"' cannot be placed as new SELL order as it is below the minimal order volume of '" +minimumOrderVolume+"'. Let the order as it is and wait until it is fulfilled.";
                            LOG.warn(() -> minMsg);
                            return;
                        }

                        boolean orderCanceled = tradingApi.cancelOrder(currentSellOrder.getId(), market.getId());

                        if (orderCanceled) {
                            LOG.warn(() -> market.getName() + " Order '" + currentSellOrder.getId() + "' successfully canceled. Place a new SELL order with the current market price to get rid of the open order.");
                            currentSellOrder = null;
                            updateStateTo(IntelligentStrategyState.NEED_SELL);
                            placeSellOrder(new OrderPriceCalculator() {
                                @Override
                                public BigDecimal calculate() throws TradingApiException, ExchangeNetworkException, StrategyException {
                                    return priceTracker.getLast();
                                }

                                @Override
                                public void logStatistics() throws TradingApiException, ExchangeNetworkException, StrategyException {

                                }
                            });
                        } else {
                            LOG.warn(() -> market.getName() + " Order '" + currentSellOrder.getId() + "' canceling failed. Maybe it was fulfilled recently on the market. Wait another tick.");
                        }
                    } else {
                        LOG.warn(() -> market.getName() + " The SELL order execution failed just '" + currentSellOrder.getOrderNotExecutedCounter() + "' times so far. Wait a bit longer for the order to be processed.");
                    }
                } else {
                    LOG.info(() -> market.getName() + " The current SELL order's price '" + priceTracker.formatWithCounterCurrency(currentSellOrderPrice)
                            + "' is below the current market price ('" + priceTracker.getFormattedLast() + "'). Check if the order must be updated and the stop limit must be increased");

                    BigDecimal sellPrice = sellOrderPriceCalcuclator.calculate();
                    if (sellPrice.compareTo(currentSellOrderPrice) > 0) {
                        LOG.info(() -> market.getName() + " The new SELL order's price '" + priceTracker.formatWithCounterCurrency(sellPrice)
                                + "' is higher than the the current sell order's price ('" + priceTracker.formatWithCounterCurrency(currentSellOrderPrice)+ "'). Cancel the current sell order '" + currentSellOrder.getId() + "' and trail the stop according to the higher stop limit.");

                        boolean orderCanceled = tradingApi.cancelOrder(currentSellOrder.getId(), market.getId());

                        if (orderCanceled) {
                            LOG.info(() -> market.getName() + " Order '" + currentSellOrder.getId() + "' successfully canceled. Reset the strategy to the sell phase...");
                            currentSellOrder = null;
                            updateStateTo(IntelligentStrategyState.NEED_SELL);
                            stateChangedListener.onStrategyChanged(NEED_SELL);
                        } else {
                            LOG.warn(() -> market.getName() + " Order '" + currentSellOrder.getId() + "' canceling failed. Maybe it was fulfilled recently on the market. Wait another tick.");
                        }
                    } else {
                        LOG.info(() -> market.getName() + " The new SELL order's price '" + priceTracker.formatWithCounterCurrency(sellPrice)
                                + "' is lower than the the current sell order's price ('" + priceTracker.formatWithCounterCurrency(currentSellOrderPrice) + "'). Wait for the order to fulfill or to increase trail in the next strategy tick.");
                    }
                }
                break;
            case UNAVAILABLE:
                LOG.info(() -> market.getName() + " SELL order '" + currentSellOrder.getId() + "' is not in the open orders anymore. Normally it was executed. Restart gaining money in the buy phase...");
                BigDecimal buyPricePerPiece = getCurrentBuyOrderPrice().add(getCurrentBuyOrderPrice().multiply(tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(market.getId())));
                BigDecimal sellPricePerPiece = currentSellOrderPrice.subtract(currentSellOrderPrice.multiply(tradingApi.getPercentageOfSellOrderTakenForExchangeFee(market.getId())));

                BigDecimal totalBuyPrice = buyPricePerPiece.multiply(currentBuyOrder.getAmount());
                BigDecimal totalSellPrice = sellPricePerPiece.multiply(currentSellOrder.getAmount());
                BigDecimal totalGain = totalSellPrice.subtract(totalBuyPrice);
                LOG.info(() -> market.getName() + " SELL order executed with a gain/loss of '" + priceTracker.formatWithCounterCurrency(totalGain) + "'. (sell order price: '" + priceTracker.formatWithCounterCurrency(currentSellOrderPrice) + "', sell order amount: '" + DECIMAL_FORMAT.format(currentSellOrder.getAmount()) + "')");
                tradeClosedListener.onTradeCloseSuccess(totalGain);
                currentBuyOrder = null;
                currentSellOrder = null;
                getBreakEvenIndicator().registerSellOrderExecution(priceTracker.getSeries().getEndIndex());
                updateStateTo(NEED_BUY);
                stateChangedListener.onStrategyChanged(NEED_BUY);
                break;
            default:
                throw new StrategyException("Unknown order market state encounted: " + marketState);
        }


    }

    private OpenOrderState retrievOrderStateFromMarket(PlacedOrder order) throws ExchangeNetworkException, TradingApiException {
        final List<OpenOrder> myOrders = getAllOpenOrdersForMarket(market.getId());
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

        BigDecimal calculatedPiecesToBuy = amountOfPiecesToBuyCalcualtor.calculate();
        BigDecimal sanitizedPiecesToBuy = sanitizeBuyAmount(calculatedPiecesToBuy);

        if (sanitizedPiecesToBuy != null) {

            LOG.info(() -> market.getName() + " BUY phase - Place a BUY order of '" + DECIMAL_FORMAT.format(sanitizedPiecesToBuy) + " * " + priceTracker.getFormattedLast() + "'");
            String orderID = tradingApi.createOrder(market.getId(), OrderType.BUY, sanitizedPiecesToBuy, priceTracker.getLast());

            LOG.info(() -> market.getName() + " BUY Order sent successfully to exchange. ID: " + orderID);

            currentBuyOrder = new PlacedOrder(orderID, OrderType.BUY, sanitizedPiecesToBuy, priceTracker.getLast());
            updateStateTo(IntelligentStrategyState.WAIT_FOR_BUY);
        }
    }

    private BigDecimal sanitizeBuyAmount(BigDecimal piecesToBuy) throws TradingApiException, ExchangeNetworkException {
        BigDecimal minimumOrderVolume = tradingApi.getMinimumOrderVolume(market.getId());
        if(piecesToBuy.compareTo(minimumOrderVolume) < 0) {
            LOG.warn(() -> market.getName() + " BUY phase - the minimum order volume of '" + DECIMAL_FORMAT.format(minimumOrderVolume) + "' was not reached by the calculated amount of pieces '" + piecesToBuy +"''");
            BigDecimal buyFee = tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(market.getId());
            BigDecimal buyFeeFactor = BigDecimal.ONE.add(buyFee);
            BigDecimal totalOrderPriceWithMinimumOrderSize = minimumOrderVolume.multiply(priceTracker.getLast()).multiply(buyFeeFactor);
            BigDecimal estimatedBalanceAfterBuy = priceTracker.getAvailableCounterCurrencyBalance().subtract(totalOrderPriceWithMinimumOrderSize);
            if (estimatedBalanceAfterBuy.compareTo(configuredEmergencyStop) > 0) {
                LOG.warn(() -> market.getName() + " BUY phase - enough balance for minimum order volume available on balance. Replace order volume with the minimum order volume of '" + DECIMAL_FORMAT.format(minimumOrderVolume) + "'");
                return minimumOrderVolume;
            } else {
                LOG.warn(() -> market.getName() + " BUY phase - the minimum order volume would lead to a balance '" + priceTracker.formatWithCounterCurrency(estimatedBalanceAfterBuy) + "' which is under the configured emergeny stop of '" + priceTracker.formatWithCounterCurrency(configuredEmergencyStop) +"''. Skip the BUY and wait until enough balance is available on the account.");
                return null;
            }
        } else {
            LOG.info(() -> market.getName() + " BUY phase - the calculated amount of pieces to buy '" + piecesToBuy + "' is above the minimum order volume of '" + DECIMAL_FORMAT.format(minimumOrderVolume) + "'. Place a buy order with the calculated amount.");
            return piecesToBuy;
        }
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
        BigDecimal sellOrderAmount = priceTracker.getAvailableBaseCurrencyBalance();
        BigDecimal minimumOrderVolume = tradingApi.getMinimumOrderVolume(market.getId());

        if (sellOrderAmount.compareTo(minimumOrderVolume)<0) {
            String errorMsg = "Tried to place a sell order of '" + sellOrderAmount + "' pieces. But the minimum order volume on market is '" + minimumOrderVolume + "'. This should hopyfully never happen.";
            throw new StrategyException(errorMsg);
        }

        LOG.info(() -> market.getName() + " SELL phase - Place a SELL order of '" + DECIMAL_FORMAT.format(sellOrderAmount) + " * " + priceTracker.formatWithCounterCurrency(sellPrice) + "'");

        String orderId = tradingApi.createOrder(market.getId(), OrderType.SELL, sellOrderAmount, sellPrice);

        LOG.info(() -> market.getName() + " SELL Order sent successfully to exchange. ID: " + orderId);

        currentSellOrder = new PlacedOrder(orderId, OrderType.SELL, sellOrderAmount, sellPrice);
        updateStateTo(IntelligentStrategyState.WAIT_FOR_SELL);
    }

    public RecordedStrategy getRecordedStrategy() throws TradingApiException, ExchangeNetworkException {
        String strategyName = "Recording for " + market.getName();
        return RecordedStrategy.createStrategyFromRecording(strategyName, getBreakEvenIndicator());
    }

    public SellIndicator getBreakEvenIndicator() throws TradingApiException, ExchangeNetworkException {
        if (breakEvenIndicator == null) {
            BigDecimal buyFee = tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(market.getId());
            BigDecimal sellFee = tradingApi.getPercentageOfSellOrderTakenForExchangeFee(market.getId());
            this.breakEvenIndicator = SellIndicator.createBreakEvenIndicator(this.priceTracker.getSeries(), buyFee, sellFee);
        }
        return breakEvenIndicator;
    }

    public interface OnStrategyStateChangeListener {
        void onStrategyChanged(IntelligentStrategyState newState) throws TradingApiException, ExchangeNetworkException, StrategyException;
    }

    public interface OnTradeSuccessfullyClosedListener {
        void onTradeCloseSuccess(BigDecimal profit);

        void logStatistics();
    }

    public interface OrderPriceCalculator {
        BigDecimal calculate() throws TradingApiException, ExchangeNetworkException, StrategyException;
        void logStatistics() throws TradingApiException, ExchangeNetworkException, StrategyException;
    }
}
