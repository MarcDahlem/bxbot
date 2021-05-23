package com.gazbert.bxbot.strategies.helper;

import static com.gazbert.bxbot.strategies.helper.IntelligentStrategyState.NEED_ENTER;
import static com.gazbert.bxbot.strategies.helper.IntelligentStrategyState.NEED_EXIT;
import static com.gazbert.bxbot.strategies.helper.IntelligentStrategyState.WAIT_FOR_ENTER;
import static com.gazbert.bxbot.strategies.helper.IntelligentStrategyState.WAIT_FOR_EXIT;

import com.gazbert.bxbot.strategies.StrategyConfigParser;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.Market;
import com.gazbert.bxbot.trading.api.OpenOrder;
import com.gazbert.bxbot.trading.api.OrderType;
import com.gazbert.bxbot.trading.api.TradingApi;
import com.gazbert.bxbot.trading.api.TradingApiException;
import com.gazbert.bxbot.trading.api.util.ta4j.ExitIndicator;
import com.gazbert.bxbot.trading.api.util.ta4j.MarketEnterType;
import com.gazbert.bxbot.trading.api.util.ta4j.RecordedStrategy;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class IntelligentStateTracker {

    private static final Logger LOG = LogManager.getLogger();
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#.########");

    private final TradingApi tradingApi;
    private final Market market;
    private final IntelligentPriceTracker priceTracker;
    private final BigDecimal configuredEmergencyStop;

    private IntelligentStrategyState strategyState;
    private PlacedOrder currentEnterOrder;
    private PlacedOrder currentExitOrder;
    private ExitIndicator breakEvenIndicator;

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
                currentEnterOrder = new PlacedOrder("DUMMY_STRATEGY_RESUMED_BUY_ORDER_DUE_TO_OPEN_BALANCE", OrderType.BUY, currentBaseCurrencyBalance, priceTracker.getLast());
                strategyState = IntelligentStrategyState.NEED_EXIT;
                getBreakEvenIndicator().registerEntryOrderExecution(priceTracker.getSeries().getEndIndex(), MarketEnterType.LONG_POSITION); // TODO compute initial state also for short trades
                return;
            } else {
                LOG.info(market.getName() + " No significant open balance in base currency found (" + priceTracker.getFormattedBaseCurrencyBalance() + "). No resume needed. Set current phase to BUY.");
                strategyState = IntelligentStrategyState.NEED_ENTER;
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
            currentEnterOrder = new PlacedOrder(currentOpenOrder.getId(), currentOpenOrder.getType(), currentOpenOrder.getOriginalQuantity(), currentOpenOrder.getPrice());
            strategyState = IntelligentStrategyState.WAIT_FOR_ENTER;
        } else {
            LOG.info(() -> market.getName() + " The current order is a SELL order. Resume with waiting for SELL to be fulfilled or changing SELL prices.");
            BigDecimal estimatedBuyPrice = priceTracker.getLast().max(currentOpenOrder.getPrice());
            currentEnterOrder = new PlacedOrder("DUMMY_STRATEGY_RESUMED_BUY_ORDER_DUE_TO_OPEN_SELL_ORDER", OrderType.BUY, currentOpenOrder.getQuantity(), estimatedBuyPrice);
            currentExitOrder = new PlacedOrder(currentOpenOrder.getId(), currentOpenOrder.getType(), currentOpenOrder.getQuantity(), currentOpenOrder.getPrice());
            getBreakEvenIndicator().registerEntryOrderExecution(priceTracker.getSeries().getEndIndex(), MarketEnterType.LONG_POSITION); // TODO compute initial state also for short trades
            strategyState = IntelligentStrategyState.WAIT_FOR_EXIT;
        }
    }

    private List<OpenOrder> getAllOpenOrdersForMarket(String id) throws ExchangeNetworkException, TradingApiException {
        long currentStrategyTick = priceTracker.getCurrentStrategyTick();
        if (!allOpenOrders.containsKey(currentStrategyTick)) {
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

    public BigDecimal getCurrentEnterOrderPrice() {
        return currentEnterOrder.getPrice();
    }

    public String getFormattedEntryOrderPrice() {
        return priceTracker.formatWithCounterCurrency(getCurrentEnterOrderPrice());
    }

    public BigDecimal getCurrentExitOrderPrice() {
        return currentExitOrder.getPrice();
    }

    public void trackRunningEnterOrder(OrderPriceCalculator amountOfPiecesToEnterCalcualtor, OnStrategyStateChangeListener listener) throws TradingApiException, ExchangeNetworkException, StrategyException {
        if (strategyState != WAIT_FOR_ENTER) {
            String errorMsg = "Invalid state encountered: " + strategyState + " while ask to track a running ENTER order. State needed: " + WAIT_FOR_ENTER;
            LOG.error(() -> market.getName() + " " + errorMsg);
            throw new StrategyException(errorMsg);
        }
        OpenOrderState marketState = retrieveOrderStateFromMarket(currentEnterOrder);

        switch (marketState) {
            case PARTIAL_AVAILABLE:
                if (currentEnterOrder.getType().equals(OrderType.BUY)) {
                    if (getCurrentEnterOrderPrice().compareTo(priceTracker.getLast()) > 0) {
                        LOG.warn(() -> market.getName() + " The order is partially filled but the current BUY order (price: '" + getFormattedEntryOrderPrice()
                                + ") is below the current market price (" + priceTracker.getFormattedLast() + ").");
                    } else {
                        LOG.warn(() -> market.getName() + " The order is partially filled but the market moved down (buy price: '" + getFormattedEntryOrderPrice()
                                + ", market price: " + priceTracker.getFormattedLast() + ").");
                    }
                } else {
                    if (getCurrentEnterOrderPrice().compareTo(priceTracker.getLast()) < 0) {
                        LOG.warn(() -> market.getName() + " The order is partially filled but the current SHORT ENTER order (price: '" + getFormattedEntryOrderPrice()
                                + ") is above the current market price (" + priceTracker.getFormattedLast() + ").");
                    } else {
                        LOG.warn(() -> market.getName() + " The order is partially filled but the market moved up (short enter price: '" + getFormattedEntryOrderPrice()
                                + ", market price: " + priceTracker.getFormattedLast() + ").");
                    }
                }
                currentEnterOrder.increaseOrderNotExecutedCounter();
                if (currentEnterOrder.getOrderNotExecutedCounter() <= 9) { // TODO make configurable + TODO reset counter
                    LOG.warn(() -> market.getName() + " The ENTER order execution failed just '" + currentEnterOrder.getOrderNotExecutedCounter() + "' times so far. Wait a bit longer for the order to be processed.");
                } else {
                    LOG.warn(() -> market.getName() + " The ENTER order execution failed '" + currentEnterOrder.getOrderNotExecutedCounter() + "' times. Waiting did not help. Cancel the rest of the order and proceed with waiting for exiting the partially filled ENTER order.");

                    boolean orderCanceled = tradingApi.cancelOrder(currentEnterOrder.getId(), market.getId());

                    if (orderCanceled) {
                        LOG.info(() -> market.getName() + " Order '" + currentEnterOrder.getId() + "' successfully canceled. Compute executed order amount for the partial order.");
                        BigDecimal filledOrderAmount = priceTracker.getAvailableBaseCurrencyBalance(); // TODO check what the market returns for partially filled or filled sell orders
                        currentEnterOrder = new PlacedOrder(currentEnterOrder.getId(), currentEnterOrder.getType(), filledOrderAmount, getCurrentEnterOrderPrice());
                        LOG.info(() -> market.getName() + " Replaced the order amount for '" + currentEnterOrder.getId() + "' successfully with '" + DECIMAL_FORMAT.format(filledOrderAmount) + "' according to the available funds on the account. Proceed with EXIT phase");
                        getBreakEvenIndicator().registerEntryOrderExecution(priceTracker.getSeries().getEndIndex(), currentEnterOrder.getMarketEnterType());
                        updateStateTo(IntelligentStrategyState.NEED_EXIT);
                        listener.onStrategyChanged(IntelligentStrategyState.NEED_EXIT);
                    } else {
                        LOG.warn(() -> market.getName() + " Order '" + currentEnterOrder.getId() + "' canceling failed. Maybe it was fulfilled recently on the market. Wait another tick.");
                    }
                }
                break;
            case FULL_AVAILABLE:
                if (getCurrentEnterOrderPrice().compareTo(priceTracker.getLast()) == 0) {
                    LOG.info(() -> market.getName() + " The current ENTER order's price '" + getFormattedEntryOrderPrice()
                            + "' is exactly on the current market price ('" + priceTracker.getFormattedLast() + "'). Wait another tick.");
                } else {
                    switch (currentEnterOrder.getMarketEnterType()) {
                        case LONG_POSITION:
                            if (getCurrentEnterOrderPrice().compareTo(priceTracker.getLast()) > 0) {
                                LOG.info(() -> market.getName() + " The current BUY order's price '" + getFormattedEntryOrderPrice()
                                        + "' is above the current market price ('" + priceTracker.getFormattedLast() + "'). Cancel the order '" + currentEnterOrder.getId() + "'.");
                            } else {
                                currentEnterOrder.increaseOrderNotExecutedCounter();
                                if (currentEnterOrder.getOrderNotExecutedCounter() <= 3) { // TODO make configurable + TODO reset counter
                                    LOG.warn(() -> market.getName() + " The BUY order execution failed just '" + currentEnterOrder.getOrderNotExecutedCounter() + "' times so far. Wait a bit longer for the order to be processed.");
                                    return;
                                } else {
                                    LOG.warn(() -> market.getName() + " The BUY order execution failed '" + currentEnterOrder.getOrderNotExecutedCounter() + "' times. Waiting did not help. Place a new BUY order to participate in the up trend.");
                                }
                            }
                            break;
                        case SHORT_POSITION:
                            if (getCurrentEnterOrderPrice().compareTo(priceTracker.getLast()) < 0) {
                                LOG.info(() -> market.getName() + " The current SHORT ENTER order's price '" + getFormattedEntryOrderPrice()
                                        + "' is below the current market price ('" + priceTracker.getFormattedLast() + "'). Cancel the order '" + currentEnterOrder.getId() + "'.");
                            } else {
                                currentEnterOrder.increaseOrderNotExecutedCounter();
                                if (currentEnterOrder.getOrderNotExecutedCounter() <= 3) { // TODO make configurable + TODO reset counter
                                    LOG.warn(() -> market.getName() + " The SHORT ENTER order execution failed just '" + currentEnterOrder.getOrderNotExecutedCounter() + "' times so far. Wait a bit longer for the order to be processed.");
                                    return;
                                } else {
                                    LOG.warn(() -> market.getName() + " The SHORT ENTER order execution failed '" + currentEnterOrder.getOrderNotExecutedCounter() + "' times. Waiting did not help. Place a new SHORT ENTER order to participate in the down trend.");
                                }
                            }
                            break;
                        default:
                            throw new IllegalStateException("Unknown market enter type " + currentEnterOrder.getMarketEnterType());
                    }
                    boolean orderCanceled = tradingApi.cancelOrder(currentEnterOrder.getId(), market.getId());

                    if (orderCanceled) {
                        LOG.info(() -> market.getName() + " Order '" + currentEnterOrder.getId() + "' successfully canceled. Reset the strategy to the ENTER phase...");
                        MarketEnterType currentMarketEnterType = currentEnterOrder.getMarketEnterType();
                        currentEnterOrder = null;
                        updateStateTo(NEED_ENTER);
                        placeEnterOrder(amountOfPiecesToEnterCalcualtor, currentMarketEnterType);
                    } else {
                        LOG.warn(() -> market.getName() + " Order '" + currentEnterOrder.getId() + "' canceling failed. Maybe it was fulfilled recently on the market. Wait another tick.");
                    }
                }
                break;
            case UNAVAILABLE:
                LOG.info(() -> market.getName() + " ENTER order '" + currentEnterOrder.getId() + "' is not in the open orders anymore. Normally it was executed. Proceed to the EXIT phase...");
                getBreakEvenIndicator().registerEntryOrderExecution(priceTracker.getSeries().getEndIndex(), currentEnterOrder.getMarketEnterType());
                updateStateTo(IntelligentStrategyState.NEED_EXIT);
                listener.onStrategyChanged(NEED_EXIT);
                break;
            default:
                throw new StrategyException("Unknown order market state encounted: " + marketState);
        }
    }

    public void trackRunningExitOrder(OrderPriceCalculator sellOrderPriceCalcuclator, OnStrategyStateChangeListener stateChangedListener, OnTradeSuccessfullyClosedListener tradeClosedListener) throws TradingApiException, ExchangeNetworkException, StrategyException {
        if (strategyState != WAIT_FOR_EXIT) {
            String errorMsg = "Invalid state encountered: " + strategyState + " while ask to track a running EXIT order. State needed: " + WAIT_FOR_EXIT;
            LOG.error(() -> market.getName() + " " + errorMsg);
            throw new StrategyException(errorMsg);
        }
        BigDecimal currentMarketPrice = priceTracker.getLast();
        BigDecimal currentExitOrderPrice = getCurrentExitOrderPrice();
        OpenOrderState marketState = retrieveOrderStateFromMarket(currentExitOrder);

        switch (marketState) {
            case PARTIAL_AVAILABLE:
            case FULL_AVAILABLE:
                LOG.info(() -> market.getName() + " EXIT order '" + currentExitOrder.getId() + "' is still available. Check if the current market price moved to the other side of the order price.");
                handleExitPositionIsStillAvailableOnMarket(sellOrderPriceCalcuclator, currentMarketPrice, currentExitOrderPrice);
                break;
            case UNAVAILABLE:
                LOG.info(() -> market.getName() + " EXIT order '" + currentExitOrder.getId() + "' is not in the open orders anymore. Normally it was executed. Restart gaining money in the ENTER phase...");
                BigDecimal totalGain = calculateGainOnPositionExit(currentExitOrderPrice);
                LOG.info(() -> market.getName() + " EXIT order executed with a gain/loss of '" + priceTracker.formatWithCounterCurrency(totalGain) + "'. (EXIT order price: '" + priceTracker.formatWithCounterCurrency(currentExitOrderPrice) + "', EXIT order amount: '" + DECIMAL_FORMAT.format(currentExitOrder.getAmount()) + "')");
                tradeClosedListener.onTradeCloseSuccess(totalGain, getCurrentMarketEntry());
                currentEnterOrder = null;
                currentExitOrder = null;
                getBreakEvenIndicator().registerExitOrderExecution(priceTracker.getSeries().getEndIndex());
                updateStateTo(NEED_ENTER);
                stateChangedListener.onStrategyChanged(NEED_ENTER);
                break;
            default:
                throw new StrategyException("Unknown order market state encounted: " + marketState);
        }
    }

    private BigDecimal calculateGainOnPositionExit(BigDecimal currentExitOrderPrice) throws TradingApiException, ExchangeNetworkException {

        BigDecimal feesOnEnteringMarket = getCurrentEnterOrderPrice().multiply(tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(market.getId()));
        BigDecimal feesOnMarketExit = currentExitOrderPrice.multiply(tradingApi.getPercentageOfSellOrderTakenForExchangeFee(market.getId()));

        BigDecimal enterPricePerPiece = null;
        BigDecimal exitPricePerPiece = null;
        BigDecimal totalExitPrice = null;
        BigDecimal totalEnterPrice = null;
        BigDecimal totalGain = null;
        switch (getCurrentMarketEntry()) {
            case LONG_POSITION:
                enterPricePerPiece = getCurrentEnterOrderPrice().add(feesOnEnteringMarket);
                exitPricePerPiece = currentExitOrderPrice.subtract(feesOnMarketExit);
                totalExitPrice = exitPricePerPiece.multiply(currentExitOrder.getAmount());
                totalEnterPrice = enterPricePerPiece.multiply(currentEnterOrder.getAmount());
                totalGain = totalExitPrice.subtract(totalEnterPrice);
                break;
            case SHORT_POSITION:
                enterPricePerPiece = getCurrentEnterOrderPrice().subtract(feesOnEnteringMarket);
                exitPricePerPiece = currentExitOrderPrice.add(feesOnMarketExit);
                totalExitPrice = exitPricePerPiece.multiply(currentEnterOrder.getAmount()); // TODO store the original account somewhere and handle partial filled orders correctly by calculating partial fees and gains. For Short orders the rebuy amount is 0
                totalEnterPrice = enterPricePerPiece.multiply(currentEnterOrder.getAmount());
                totalGain = totalEnterPrice.subtract(totalExitPrice);
                break;
            default: throw new IllegalStateException("Unknown market entry type occured: " + getCurrentMarketEntry());
        }
        return totalGain;
    }

    private void handleExitPositionIsStillAvailableOnMarket(OrderPriceCalculator sellOrderPriceCalcuclator, BigDecimal currentMarketPrice, BigDecimal currentExitOrderPrice) throws ExchangeNetworkException, TradingApiException, StrategyException {
        switch (currentEnterOrder.getMarketEnterType()) {
            case SHORT_POSITION:
                handleShortExitPositionIsStillAvailableInMarket(sellOrderPriceCalcuclator, currentMarketPrice, currentExitOrderPrice);
                break;
            case LONG_POSITION:
                handleLongExitPositionIsStillAvailableInMarket(sellOrderPriceCalcuclator, currentMarketPrice, currentExitOrderPrice);
                break;
            default:
                throw new IllegalStateException("Unkown market entry type " + getCurrentMarketEntry());
        }
    }

    private void handleLongExitPositionIsStillAvailableInMarket(OrderPriceCalculator sellOrderPriceCalcuclator, BigDecimal currentMarketPrice, BigDecimal currentExitOrderPrice) throws ExchangeNetworkException, TradingApiException, StrategyException {
        if (currentExitOrderPrice.compareTo(currentMarketPrice) > 0) {
            LOG.warn(() -> market.getName() + " The current SELL order is above the current market price. It should soon be fulfilled.");
            currentExitOrder.increaseOrderNotExecutedCounter();
            if (currentExitOrder.getOrderNotExecutedCounter() >= 10) { // TODO make 10 configurable or another approach
                String msg = market.getName() + " The current SELL order was " + currentExitOrder.getOrderNotExecutedCounter() + " times above the current market price. It should normally be fulfilled. Cancel the order and place directly a sell order with the current markets price.";
                LOG.warn(() -> msg);
                BigDecimal availableBaseCurrencyBalance = priceTracker.getAvailableBaseCurrencyBalance();
                BigDecimal minimumOrderVolume = tradingApi.getMinimumOrderVolume(market.getId());
                if (minimumOrderVolume.compareTo(availableBaseCurrencyBalance) > 0) {
                    String minMsg = market.getName() + " The current SELL order was partially filled. The remaining volume '" + priceTracker.getFormattedBaseCurrencyBalance() + "' cannot be placed as new SELL order as it is below the minimal order volume of '" + minimumOrderVolume + "'. Let the order as it is and wait until it is fulfilled.";
                    LOG.warn(() -> minMsg);
                    return;
                }

                boolean orderCanceled = tradingApi.cancelOrder(currentExitOrder.getId(), market.getId());

                if (orderCanceled) {
                    LOG.warn(() -> market.getName() + " Order '" + currentExitOrder.getId() + "' successfully canceled. Place a new SELL order with the current market price to get rid of the open order.");
                    currentExitOrder = null;
                    updateStateTo(IntelligentStrategyState.NEED_EXIT);
                    placeExitOrder(new OrderPriceCalculator() {
                        @Override
                        public BigDecimal calculate(MarketEnterType type) throws TradingApiException, ExchangeNetworkException, StrategyException {
                            return priceTracker.getLast();
                        }

                        @Override
                        public void logStatistics(MarketEnterType type) throws TradingApiException, ExchangeNetworkException, StrategyException {

                        }
                    });
                } else {
                    LOG.warn(() -> market.getName() + " Order '" + currentExitOrder.getId() + "' canceling failed. Maybe it was fulfilled recently on the market. Wait another tick.");
                }
            } else {
                LOG.warn(() -> market.getName() + " The SELL order execution failed just '" + currentExitOrder.getOrderNotExecutedCounter() + "' times so far. Wait a bit longer for the order to be processed.");
            }
        } else {
            LOG.info(() -> market.getName() + " The current SELL order's price '" + priceTracker.formatWithCounterCurrency(currentExitOrderPrice)
                    + "' is below the current market price ('" + priceTracker.getFormattedLast() + "'). Check if the order must be updated and the stop limit must be increased");

            BigDecimal sellPrice = sellOrderPriceCalcuclator.calculate(currentEnterOrder.getMarketEnterType());
            if (sellPrice.compareTo(currentExitOrderPrice) > 0) {
                LOG.info(() -> market.getName() + " The new SELL order's price '" + priceTracker.formatWithCounterCurrency(sellPrice)
                        + "' is higher than the the current sell order's price ('" + priceTracker.formatWithCounterCurrency(currentExitOrderPrice) + "'). Cancel the current sell order '" + currentExitOrder.getId() + "' and trail the stop according to the higher stop limit.");

                boolean orderCanceled = tradingApi.cancelOrder(currentExitOrder.getId(), market.getId());

                if (orderCanceled) {
                    LOG.info(() -> market.getName() + " Order '" + currentExitOrder.getId() + "' successfully canceled. Reset the strategy to the sell phase...");
                    currentExitOrder = null;
                    updateStateTo(IntelligentStrategyState.NEED_EXIT);
                    placeExitOrder(sellOrderPriceCalcuclator);
                } else {
                    LOG.warn(() -> market.getName() + " Order '" + currentExitOrder.getId() + "' canceling failed. Maybe it was fulfilled recently on the market. Wait another tick.");
                }
            } else {
                LOG.info(() -> market.getName() + " The new SELL order's price '" + priceTracker.formatWithCounterCurrency(sellPrice)
                        + "' is lower than the the current sell order's price ('" + priceTracker.formatWithCounterCurrency(currentExitOrderPrice) + "'). Wait for the order to fulfill or to increase trail in the next strategy tick.");
            }
        }
    }

    private void handleShortExitPositionIsStillAvailableInMarket(OrderPriceCalculator exitOrderPriceCalcuclator, BigDecimal currentMarketPrice, BigDecimal currentExitOrderPrice) throws ExchangeNetworkException, TradingApiException, StrategyException {
        if (currentExitOrderPrice.compareTo(currentMarketPrice) < 0) {
            LOG.warn(() -> market.getName() + " The current BUY (short exit) order is below the current market price. It should soon be fulfilled.");
            currentExitOrder.increaseOrderNotExecutedCounter();
            if (currentExitOrder.getOrderNotExecutedCounter() >= 10) { // TODO make 10 configurable or another approach
                String msg = market.getName() + " The current BUY (short exit) order was " + currentExitOrder.getOrderNotExecutedCounter() + " times below the current market price. It should normally be fulfilled. Cancel the order and place directly a new BUY (short exit) order with the current markets price.";
                LOG.warn(() -> msg);
                BigDecimal availableBaseCurrencyBalance = priceTracker.getAvailableBaseCurrencyBalance(); // TODO check what is in here for short orders
                BigDecimal minimumOrderVolume = tradingApi.getMinimumOrderVolume(market.getId());
                if (minimumOrderVolume.compareTo(availableBaseCurrencyBalance.abs()) > 0) {
                    String minMsg = market.getName() + " The current BUY (short exit) order was partially filled. The remaining volume '" + priceTracker.getFormattedBaseCurrencyBalance() + "' cannot be placed as new SELL order as it is below the minimal order volume of '" + minimumOrderVolume + "'. Let the order as it is and wait until it is fulfilled.";
                    LOG.warn(() -> minMsg);
                    return;
                }

                boolean orderCanceled = tradingApi.cancelOrder(currentExitOrder.getId(), market.getId());

                if (orderCanceled) {
                    LOG.warn(() -> market.getName() + " Order '" + currentExitOrder.getId() + "' successfully canceled. Place a new BUY (short) order with the current market price to get rid of the open order.");
                    currentExitOrder = null;
                    updateStateTo(IntelligentStrategyState.NEED_EXIT);
                    placeExitOrder(new OrderPriceCalculator() {
                        @Override
                        public BigDecimal calculate(MarketEnterType type) throws TradingApiException, ExchangeNetworkException, StrategyException {
                            return priceTracker.getLast();
                        }

                        @Override
                        public void logStatistics(MarketEnterType type) throws TradingApiException, ExchangeNetworkException, StrategyException {

                        }
                    });
                } else {
                    LOG.warn(() -> market.getName() + " Order '" + currentExitOrder.getId() + "' canceling failed. Maybe it was fulfilled recently on the market. Wait another tick.");
                }
            } else {
                LOG.warn(() -> market.getName() + " The EXIT (short) order execution failed just '" + currentExitOrder.getOrderNotExecutedCounter() + "' times so far. Wait a bit longer for the order to be processed.");
            }
        } else {
            LOG.info(() -> market.getName() + " The current BUY (short exit) order's price '" + priceTracker.formatWithCounterCurrency(currentExitOrderPrice)
                    + "' is above the current market price ('" + priceTracker.getFormattedLast() + "'). Check if the order must be updated and the limit must be increased");

            BigDecimal rebuyShortPrice = exitOrderPriceCalcuclator.calculate(currentEnterOrder.getMarketEnterType());
            if (rebuyShortPrice.compareTo(currentExitOrderPrice) < 0) {
                LOG.info(() -> market.getName() + " The new BUY (short exit) order's price '" + priceTracker.formatWithCounterCurrency(rebuyShortPrice)
                        + "' is lower than the the current BUY (short exit) order's price ('" + priceTracker.formatWithCounterCurrency(currentExitOrderPrice) + "'). Cancel the current BUY (short exit) order '" + currentExitOrder.getId() + "' and trail the limit according down to the lower short rebuy limit.");

                // TODO do this only if there is enough balance. Also for long exits

                boolean orderCanceled = tradingApi.cancelOrder(currentExitOrder.getId(), market.getId());

                if (orderCanceled) {
                    LOG.info(() -> market.getName() + " Order '" + currentExitOrder.getId() + "' successfully canceled. Reset the strategy to the EXIT phase...");
                    currentExitOrder = null;
                    updateStateTo(IntelligentStrategyState.NEED_EXIT);
                    placeExitOrder(exitOrderPriceCalcuclator);
                } else {
                    LOG.warn(() -> market.getName() + " Order '" + currentExitOrder.getId() + "' canceling failed. Maybe it was fulfilled recently on the market. Wait another tick.");
                }
            } else {
                LOG.info(() -> market.getName() + " The new BUY (short exit) order's price '" + priceTracker.formatWithCounterCurrency(rebuyShortPrice)
                        + "' is higher than the the current BUY (short exit) order's price ('" + priceTracker.formatWithCounterCurrency(currentExitOrderPrice) + "'). Wait for the order to fulfill or to decrease trail in the next strategy tick.");
            }
        }
    }

    private OpenOrderState retrieveOrderStateFromMarket(PlacedOrder order) throws ExchangeNetworkException, TradingApiException {
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

    public void placeEnterOrder(OrderPriceCalculator amountOfPiecesToEnterCalcualtor, MarketEnterType marketEnterType) throws TradingApiException, ExchangeNetworkException, StrategyException {
        if (strategyState != NEED_ENTER) {
            String errorMsg = "Invalid state encountered: " + strategyState + " while ask to place a ENTER order. State needed: " + NEED_ENTER;
            LOG.error(() -> market.getName() + " " + errorMsg);
            throw new StrategyException(errorMsg);
        }
        if (currentEnterOrder != null) {
            String errorMsg = "The ENTER phase is to be executed, but there is still an open order '" + currentEnterOrder + "'. This should never happen. Stop the bot!";
            LOG.error(() -> errorMsg);
            throw new StrategyException(errorMsg);
        }

        BigDecimal calculatedPiecesToEnter = amountOfPiecesToEnterCalcualtor.calculate(marketEnterType);
        BigDecimal sanitizedPiecesToEnter = sanitizeEnterAmount(calculatedPiecesToEnter);

        if (sanitizedPiecesToEnter != null) {

            LOG.info(() -> market.getName() + " ENTER phase - Place a ENTER order of type: " + marketEnterType + ", pieces '" + DECIMAL_FORMAT.format(sanitizedPiecesToEnter) + " * " + priceTracker.getFormattedLast() + "'");
            OrderType orderType = marketEnterType == MarketEnterType.SHORT_POSITION ? OrderType.SHORT_ENTER : OrderType.BUY;
            String orderID = tradingApi.createOrder(market.getId(), orderType, sanitizedPiecesToEnter, priceTracker.getLast());
            priceTracker.adaptBalanceDueToBuyEvent(sanitizedPiecesToEnter, priceTracker.getLast(), marketEnterType);

            LOG.info(() -> market.getName() + " ENTER Order sent successfully to exchange. ID: " + orderID + ". Type: " + marketEnterType);

            currentEnterOrder = new PlacedOrder(orderID, orderType, sanitizedPiecesToEnter, priceTracker.getLast());
            updateStateTo(IntelligentStrategyState.WAIT_FOR_ENTER);
        }
    }

    private BigDecimal sanitizeEnterAmount(BigDecimal piecesToEnter) throws TradingApiException, ExchangeNetworkException {
        BigDecimal minimumOrderVolume = tradingApi.getMinimumOrderVolume(market.getId());
        if (piecesToEnter.compareTo(minimumOrderVolume) < 0) {
            LOG.warn(() -> market.getName() + " ENTER phase - the minimum order volume of '" + DECIMAL_FORMAT.format(minimumOrderVolume) + "' was not reached by the calculated amount of pieces '" + piecesToEnter + "''");
            BigDecimal enterFee = tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(market.getId());
            BigDecimal enterFeeFactor = BigDecimal.ONE.add(enterFee);
            BigDecimal totalOrderPriceWithMinimumOrderSize = minimumOrderVolume.multiply(priceTracker.getLast()).multiply(enterFeeFactor);
            BigDecimal estimatedBalanceAfterEnter = priceTracker.getAvailableCounterCurrencyBalance().subtract(totalOrderPriceWithMinimumOrderSize);
            if (estimatedBalanceAfterEnter.compareTo(configuredEmergencyStop) > 0) {
                LOG.warn(() -> market.getName() + " ENTER phase - enough balance for minimum order volume available on balance. Replace order volume with the minimum order volume of '" + DECIMAL_FORMAT.format(minimumOrderVolume) + "'");
                return minimumOrderVolume;
            } else {
                LOG.warn(() -> market.getName() + " ENTER phase - the minimum order volume would lead to a balance '" + priceTracker.formatWithCounterCurrency(estimatedBalanceAfterEnter) + "' which is under the configured emergeny stop of '" + priceTracker.formatWithCounterCurrency(configuredEmergencyStop) + "''. Skip ENTER and wait until enough balance is available on the account.");
                return null;
            }
        } else {
            LOG.info(() -> market.getName() + " ENTER phase - the calculated amount of pieces to enter '" + piecesToEnter + "' is above the minimum order volume of '" + DECIMAL_FORMAT.format(minimumOrderVolume) + "'. Place a enter order with the calculated amount.");
            return piecesToEnter;
        }
    }

    public void placeExitOrder(OrderPriceCalculator exitPriceCalculator) throws TradingApiException, ExchangeNetworkException, StrategyException {
        if (strategyState != NEED_EXIT) {
            String errorMsg = "Invalid state encountered: " + strategyState + " while ask to place a EXIT order. State needed: " + NEED_EXIT;
            LOG.error(() -> market.getName() + " " + errorMsg);
            throw new StrategyException(errorMsg);
        }
        if (currentExitOrder != null) {
            String errorMsg = "The EXIT phase is to be executed, but there is still an open order '" + currentExitOrder + "'. This should never happen. Stop the bot!";
            LOG.error(() -> errorMsg);
            throw new StrategyException(errorMsg);
        }

        BigDecimal exitPrice = exitPriceCalculator.calculate(currentEnterOrder.getMarketEnterType());
        BigDecimal exitOrderAmount = calculateAmountToUseForExitOrder();

        LOG.info(() -> market.getName() + " EXIT phase - Place a EXIT order of type " + currentEnterOrder.getMarketEnterType() + " with '" + DECIMAL_FORMAT.format(exitOrderAmount) + " * " + priceTracker.formatWithCounterCurrency(exitPrice) + "'");

        OrderType orderType = currentEnterOrder.getMarketEnterType().equals(MarketEnterType.SHORT_POSITION) ? OrderType.SHORT_EXIT : OrderType.SELL;
        String orderId = tradingApi.createOrder(market.getId(), orderType, exitOrderAmount, exitPrice);

        LOG.info(() -> market.getName() + " EXIT Order sent successfully to exchange. ID: " + orderId + ". Type: " + orderType);

        currentExitOrder = new PlacedOrder(orderId, orderType, exitOrderAmount, exitPrice);
        updateStateTo(IntelligentStrategyState.WAIT_FOR_EXIT);
    }

    private BigDecimal calculateAmountToUseForExitOrder() throws ExchangeNetworkException, TradingApiException, StrategyException {
        switch (currentEnterOrder.getMarketEnterType()) {
            case LONG_POSITION:
                BigDecimal exitOrderAmount = priceTracker.getAvailableBaseCurrencyBalance();
                BigDecimal minimumOrderVolume = tradingApi.getMinimumOrderVolume(market.getId());

                if (exitOrderAmount.compareTo(minimumOrderVolume) < 0) {
                    String errorMsg = "Tried to place a sell order of '" + exitOrderAmount + "' pieces. But the minimum order volume on market is '" + minimumOrderVolume + "'. This should hopyfully never happen.";
                    throw new StrategyException(errorMsg);
                }
                return exitOrderAmount;
            case SHORT_POSITION:
                return BigDecimal.ZERO; //can return Zero, as Kraken buys as much as needed to close the position
            default:
                throw new IllegalStateException("Unkown market enter type encountered: " + currentEnterOrder.getMarketEnterType());
        }
    }

    public RecordedStrategy getRecordedStrategy() throws TradingApiException, ExchangeNetworkException {
        String strategyName = "Recording for " + market.getName();
        return RecordedStrategy.createStrategyFromRecording(strategyName, getBreakEvenIndicator());
    }

    public ExitIndicator getBreakEvenIndicator() throws TradingApiException, ExchangeNetworkException {
        if (breakEvenIndicator == null) {
            BigDecimal enterFee = tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(market.getId());
            BigDecimal exitFee = tradingApi.getPercentageOfSellOrderTakenForExchangeFee(market.getId());
            this.breakEvenIndicator = ExitIndicator.createBreakEvenIndicator(this.priceTracker.getSeries(), enterFee, exitFee);
        }
        return breakEvenIndicator;
    }

    public MarketEnterType getCurrentMarketEntry() {
        if (currentEnterOrder == null) {
            throw new IllegalStateException("No current open order found, therefore the current maraket enter type cannot be determined");
        }
        return currentEnterOrder.getMarketEnterType();
    }

    public interface OnStrategyStateChangeListener {
        void onStrategyChanged(IntelligentStrategyState newState) throws TradingApiException, ExchangeNetworkException, StrategyException;
    }

    public interface OnTradeSuccessfullyClosedListener {
        void onTradeCloseSuccess(BigDecimal profit, MarketEnterType marketEnterType);
        void logStatistics();
    }

    public interface OrderPriceCalculator {
        BigDecimal calculate(MarketEnterType marketEnterType) throws TradingApiException, ExchangeNetworkException, StrategyException;

        void logStatistics(MarketEnterType marketEnterType) throws TradingApiException, ExchangeNetworkException, StrategyException;
    }
}
