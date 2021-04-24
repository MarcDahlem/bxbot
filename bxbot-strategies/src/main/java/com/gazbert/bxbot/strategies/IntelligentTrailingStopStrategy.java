/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Gareth Jon Lynch
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.gazbert.bxbot.strategies;

import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.strategy.api.TradingStrategy;
import com.gazbert.bxbot.trading.api.*;
import com.gazbert.bxbot.trading.api.util.JsonBarsSerializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.ta4j.core.*;
import org.ta4j.core.num.Num;

@Component("intelligentTrailingStopStrategy") // used to load the strategy using Spring bean injection
public class IntelligentTrailingStopStrategy implements TradingStrategy {

    private static final Logger LOG = LogManager.getLogger();

    /**
     * The decimal format for the logs.
     */
    private static final String DECIMAL_FORMAT = "#.########";
    private static final DecimalFormat decimalFormat = new DecimalFormat(DECIMAL_FORMAT);
    private static final BigDecimal oneHundred = new BigDecimal(100);
    private static final BigDecimal MINIMAL_ACCOUNT_BALANCE_FOR_RESUME_SELL = new BigDecimal(0.00000002);
    private static final BigDecimal twentyFive = new BigDecimal(25);

    /**
     * Reference to the main Trading API.
     */
    private TradingApi tradingApi;

    /**
     * The market this strategy is trading on.
     */

    private Market market;

    private IntelligentStrategyState strategyState;
    /**
     * The % of the the available counter currency balance to be used for buy orders. This was loaded from the strategy
     * entry in the {project-root}/config/strategies.yaml config file.
     */
    private BigDecimal configuredPercentageOfCounterCurrencyBalanceToUse;
    /**
     * The emergency stop that should always be left over in the counter currency balance.
     * This is an entry in the {project-root}/config/strategies.yaml config file.
     * It should be the same as the value of 'emergencyStopBalance' in the {project-root}/config/engine.yaml
     */
    private BigDecimal configuredEmergencyStop;

    /* market data downloaded and stored during the engine lifetime */
    private Ticker currentTicker;
    private BarSeries series;

    /* used to store the latest executed orders */
    private OrderState currentBuyOrder;
    private OrderState currentSellOrder;

    private IntelligentLimitAdapter intelligentLimitAdapter;
    private boolean debugModeEnabled;


    /**
     * Initialises the Trading Strategy. Called once by the Trading Engine when the bot starts up;
     * it's a bit like a servlet init() method.
     *
     * @param tradingApi the Trading API. Use this to make trades and stuff.
     * @param market     the market for this strategy. This is the market the strategy is currently
     *                   running on - you wire this up in the markets.yaml and strategies.yaml files.
     * @param config     configuration for the strategy. Contains any (optional) config you set up in the
     *                   strategies.yaml file.
     */
    @Override
    public void init(TradingApi tradingApi, Market market, StrategyConfig config) {
        LOG.info(() -> "Initialising Trading Strategy...");
        this.tradingApi = tradingApi;
        this.market = market;
        getConfigForStrategy(config);
        this.intelligentLimitAdapter = new IntelligentLimitAdapter(config);
        BarSeries loadedSeries = JsonBarsSerializer.loadSeries("not_available.json");
        if (loadedSeries == null) {
            LOG.info(() -> "No series to be loaded found. Create a new series.");
            loadedSeries = new BaseBarSeriesBuilder().withName(market.getName() + "_" + System.currentTimeMillis()).build();
        }
        series = loadedSeries;
        LOG.info(() -> "Trading Strategy initialised successfully!");
    }

    /**
     * This is the main execution method of the Trading Strategy. It is where your algorithm lives.
     *
     * <p>It is called by the Trading Engine during each trade cycle, e.g. every 60s. The trade cycle
     * is configured in the {project-root}/config/engine.yaml file.
     *
     * @throws StrategyException if something unexpected occurs. This tells the Trading Engine to
     *                           shutdown the bot immediately to help prevent unexpected losses.
     */
    @Override
    public void execute() throws StrategyException {

        try {

            updateMarketPrices();
            if (strategyState == null) {
                LOG.info(() -> market.getName() + " First time that the strategy has been called - get the initial strategy state.");
                computeInitialStrategyState();
                LOG.info(() -> market.getName() + " Initial strategy state computed: " + this.strategyState);
            }
            intelligentLimitAdapter.printCurrentStatistics();
            switch (strategyState) {
                case NEED_BUY:
                    executeBuyPhase();
                    break;
                case NEED_SELL:
                    executeSellPhase();
                    break;
                case WAIT_FOR_BUY:
                    executeCheckOfTheBuyOrder();
                    break;
                case WAIT_FOR_SELL:
                    executeCheckOfTheSellOrder();
                    break;
                default:
                    throw new StrategyException("Unknown strategy state encounted: " + strategyState);
            }
        } catch (TradingApiException | ExchangeNetworkException | StrategyException e) {
            // We are just going to re-throw as StrategyException for engine to deal with - it will
            // shutdown the bot.
            // TODO reanable JsonBarsSerializer.persistSeries(series, market.getId() + System.currentTimeMillis() + ".json");

            LOG.error(
                    market.getName()
                            + " Failed to perform the strategy because Exchange threw TradingApiException, ExchangeNetworkexception or StrategyException. "
                            + "Telling Trading Engine to shutdown bot!",
                    e);
            throw new StrategyException(e);
        }
    }

    private void executeCheckOfTheBuyOrder() throws TradingApiException, ExchangeNetworkException, StrategyException {
        LOG.info(() -> market.getName() + " State: Wait for BUY order to fulfill.");
        OpenOrderState marketState = retrievOrderStateFromMarket(currentBuyOrder);

        switch (marketState) {
            case PARTIAL_AVAILABLE:
                if (currentBuyOrder.getPrice().compareTo(currentTicker.getAsk()) > 0) {
                    LOG.warn(() -> market.getName() + " The current BUY order (price: '" + decimalFormat.format(currentBuyOrder.getPrice()) + " " + market.getCounterCurrency()
                            + ") is below the current market ask price (" + decimalFormat.format(currentTicker.getAsk()) + " " + market.getCounterCurrency() +
                            ").");
                } else {
                    LOG.warn(() -> market.getName() + " The order is partially filled but the market moved down (buy price: '" + decimalFormat.format(currentBuyOrder.getPrice()) + " " + market.getCounterCurrency()
                            + ", market ask price: " + decimalFormat.format(currentTicker.getAsk()) + " " + market.getCounterCurrency() +
                            ").");
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
                        BigDecimal filledOrderAmount = getAvailableCurrencyBalance(market.getCounterCurrency());
                        currentBuyOrder = new OrderState(currentBuyOrder.getId(), currentBuyOrder.getType(), filledOrderAmount, currentBuyOrder.getPrice());
                        LOG.info(() -> market.getName() + " Replaced the order amount for '" + currentBuyOrder.getId() + "' successfully with '" + decimalFormat.format(filledOrderAmount) + "' according to the available funds on the account. Proceed with SELL phase");
                        strategyState = IntelligentStrategyState.NEED_SELL;
                        executeSellPhase();
                    } else {
                        LOG.warn(() -> market.getName() + " Order '" + currentBuyOrder.getId() + "' canceling failed. Maybe it was fulfilled recently on the market. Wait another tick.");
                    }
                }
                break;
            case FULL_AVAILABLE:
                if (currentBuyOrder.getPrice().compareTo(currentTicker.getAsk()) == 0) {
                    LOG.info(() -> market.getName() + " The current buy order's price '" + decimalFormat.format(currentBuyOrder.getPrice()) + " " + market.getCounterCurrency()
                            + "' is exactly on the current market ask-price ('" + decimalFormat.format(currentTicker.getAsk()) + " " + market.getCounterCurrency() + "'). Wait another tick.");
                } else {
                    if (currentBuyOrder.getPrice().compareTo(currentTicker.getAsk()) > 0) {
                        LOG.info(() -> market.getName() + " The current BUY order's price '" + decimalFormat.format(currentBuyOrder.getPrice()) + " " + market.getCounterCurrency()
                                + "' is above the current market ask-price ('" + decimalFormat.format(currentTicker.getAsk()) + " " + market.getCounterCurrency() + "'). Cancel the order '" + currentBuyOrder.getId() + "'.");
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
                        strategyState = IntelligentStrategyState.NEED_BUY;
                        executeBuyPhase();
                    } else {
                        LOG.warn(() -> market.getName() + " Order '" + currentBuyOrder.getId() + "' canceling failed. Maybe it was fulfilled recently on the market. Wait another tick.");
                    }
                }
                break;
            case UNAVAILABLE:
                LOG.info(() -> market.getName() + " BUY order '" + currentBuyOrder.getId() + "' is not in the open orders anymore. Normally it was executed. Proceed to the sell phase...");
                strategyState = IntelligentStrategyState.NEED_SELL;
                executeSellPhase();
                break;
            default:
                throw new StrategyException("Unknown order market state encounted: " + marketState);
        }


    }

    private OpenOrderState retrievOrderStateFromMarket(OrderState order) throws ExchangeNetworkException, TradingApiException {
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

    private void executeCheckOfTheSellOrder() throws TradingApiException, ExchangeNetworkException, StrategyException {
        LOG.info(() -> market.getName() + " State: Wait for SELL order to fulfill.");

        BigDecimal currentMarketBidPrice = currentTicker.getBid();
        BigDecimal currentSellOrderPrice = currentSellOrder.getPrice();
        OpenOrderState marketState = retrievOrderStateFromMarket(currentSellOrder);
        BigDecimal breakEven = calculateBreakEven();

        switch (marketState) {
            case PARTIAL_AVAILABLE:
            case FULL_AVAILABLE:
                BigDecimal aboveBreakEvenPriceLimit = calculateAboveBreakEvenPriceLimit();
                BigDecimal belowBreakEvenPriceLimit = calculateBelowBreakEvenPriceLimit();
                BigDecimal minimumAboveBreakEvenPriceLimit = calculateMinimumAboveBreakEvenPriceLimit(breakEven);
                BigDecimal currentBuyOrderPrice = currentBuyOrder.getPrice();
                BigDecimal percentageChangeToBuyOrder = getPercentageChange(currentMarketBidPrice, currentBuyOrderPrice);
                BigDecimal percentageChangeToBreakEven = getPercentageChange(currentMarketBidPrice, breakEven);
                BigDecimal percentageChangeCurrentSellToMarket = getPercentageChange(currentSellOrderPrice, currentMarketBidPrice);
                BigDecimal percentageChangeCurrentSellToBreakEven = getPercentageChange(currentSellOrderPrice, breakEven);
                BigDecimal percentageChangeCurrentSellToBuy = getPercentageChange(currentSellOrderPrice, currentBuyOrderPrice);

                LOG.info(() -> market.getName() + " SELL order '" + currentSellOrder.getId() + "' is still available. Current sell statistics: \n" +
                        "######### SELL ORDER STATISTICS #########\n" +
                        "current market price (last): " + decimalFormat.format(currentTicker.getLast()) + " " + market.getCounterCurrency() + "\n" +
                        "current market price (bid): " + decimalFormat.format(currentTicker.getBid()) + " " + market.getCounterCurrency() + "\n" +
                        "current market price (ask): " + decimalFormat.format(currentTicker.getAsk()) + " " + market.getCounterCurrency() + "\n" +
                        "current BUY order price: " + decimalFormat.format(currentBuyOrderPrice) + " " + market.getCounterCurrency() + "\n" +
                        "current SELL order price: " + decimalFormat.format(currentSellOrderPrice) + " " + market.getCounterCurrency() + "\n" +
                        "break even: " + decimalFormat.format(breakEven) + " " + market.getCounterCurrency() + "\n" +
                        "-----------------------------------------\n" +
                        "market change (bid) to buy price: " + decimalFormat.format(percentageChangeToBuyOrder) + "%\n" +
                        "market change (bid) to break even: " + decimalFormat.format(percentageChangeToBreakEven) + "%\n" +
                        "current sell price to buy price: " + decimalFormat.format(percentageChangeCurrentSellToBuy) + "%\n" +
                        "current sell price to market (bid): " + decimalFormat.format(percentageChangeCurrentSellToMarket) + "%\n" +
                        "current sell price to break even: " + decimalFormat.format(percentageChangeCurrentSellToBreakEven) + "%\n" +
                        "-----------------------------------------\n" +
                        "limit above break even: " + decimalFormat.format(aboveBreakEvenPriceLimit) + " " + market.getCounterCurrency() + "\n" +
                        "limit minimum above break even: " + decimalFormat.format(minimumAboveBreakEvenPriceLimit) + " " + market.getCounterCurrency() + "\n" +
                        "limit below break even: " + decimalFormat.format(belowBreakEvenPriceLimit) + " " + market.getCounterCurrency() + "\n" +
                        "#########################################"

                );
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
                    LOG.info(() -> market.getName() + " The current SELL order's price '" + decimalFormat.format(currentSellOrderPrice) + " " + market.getCounterCurrency()
                            + "' is below the current market bid price ('" + decimalFormat.format(currentMarketBidPrice) + " " + market.getCounterCurrency() + "'). Check if the order must be updated and the stop limit must be increased");

                    BigDecimal sellPrice = computeCurrentSellPrice();
                    if (sellPrice.compareTo(currentSellOrderPrice) > 0) {
                        LOG.info(() -> market.getName() + " The new SELL order's price '" + decimalFormat.format(sellPrice) + " " + market.getCounterCurrency()
                                + "' is higher than the the current sell order's price ('" + decimalFormat.format(currentSellOrderPrice) + " " + market.getCounterCurrency() + "'). Cancel the current sell order '" + currentSellOrder.getId() + "' and trail the stop according to the higher stop limit.");

                        boolean orderCanceled = true;
                        if (!debugModeEnabled){
                            orderCanceled = tradingApi.cancelOrder(currentSellOrder.getId(), market.getId());
                        }

                        if (orderCanceled) {
                        LOG.info(() -> market.getName() + " Order '" + currentSellOrder.getId() + "' successfully canceled. Reset the strategy to the sell phase...");
                        currentSellOrder = null;
                        strategyState = IntelligentStrategyState.NEED_SELL;
                        executeSellPhase();
                        } else {
                            LOG.warn(() -> market.getName() + " Order '" + currentBuyOrder.getId() + "' canceling failed. Maybe it was fulfilled recently on the market. Wait another tick.");
                        }
                    } else {
                        LOG.info(() -> market.getName() + " The new SELL order's price '" + decimalFormat.format(sellPrice) + " " + market.getCounterCurrency()
                                + "' is lower than the the current sell order's price ('" + decimalFormat.format(currentSellOrderPrice) + " " + market.getCounterCurrency() + "'). Wait for the order to fulfill or to increase trail in the next strategy tick.");
                    }
                }
                break;
            case UNAVAILABLE:
                LOG.info(() -> market.getName() + " SELL order '" + currentSellOrder.getId() + "' is not in the open orders anymore. Normally it was executed. Restart gaining money in the buy phase...");
                BigDecimal totalBuyPrice = currentBuyOrder.getPrice().add(currentBuyOrder.getPrice().multiply(tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(market.getId())));
                BigDecimal totalSellPrice = currentSellOrderPrice.subtract(currentSellOrderPrice.multiply(tradingApi.getPercentageOfSellOrderTakenForExchangeFee(market.getId())));
                BigDecimal totalGain = totalSellPrice.subtract(totalBuyPrice).multiply(currentSellOrder.getAmount());
                LOG.info(() -> market.getName() + " SELL order executed with a gain/loss of '" + decimalFormat.format(totalGain) + "'. (Break even: '" + decimalFormat.format(breakEven) + "', sell order price: '" + decimalFormat.format(currentSellOrderPrice) + "', sell order amount: '" + decimalFormat.format(currentSellOrder.getAmount()) + "')");
                intelligentLimitAdapter.addNewExecutedSellOrder(currentSellOrder, totalGain, breakEven);
                currentBuyOrder = null;
                currentSellOrder = null;
                strategyState = IntelligentStrategyState.NEED_BUY;
                executeBuyPhase();
                break;
            default:
                throw new StrategyException("Unknown order market state encounted: " + marketState);
        }


    }

    private BigDecimal getPercentageChange(BigDecimal newPrice, BigDecimal priceToCompareAgainst) {
        return newPrice.subtract(priceToCompareAgainst).divide(priceToCompareAgainst, 10, RoundingMode.HALF_UP).multiply(oneHundred);
    }

    private void executeBuyPhase() throws TradingApiException, ExchangeNetworkException, StrategyException {
        LOG.info(() -> market.getName() + " BUY phase - check if the market moved up.");
        if (currentBuyOrder != null) {
            String errorMsg = "The BUY phase is to be executed, but there is still an open order '" + currentBuyOrder + "'. This should never happen. Stop the bot!";
            LOG.error(() -> errorMsg);
            throw new StrategyException(errorMsg);
        }

        if (marketMovedUp()) {
            LOG.info(() -> market.getName() + " BUY phase - The market moved up. Place a BUY order on the exchange -->");
            final BigDecimal piecesToBuy = getAmountOfPiecesToBuy();

            LOG.info(() -> market.getName() + " BUY phase - Place a BUY order of '" + decimalFormat.format(piecesToBuy) + " * " + decimalFormat.format(currentTicker.getAsk()) + " " + market.getCounterCurrency() + "'");
            String orderID;
            if (debugModeEnabled) {
                orderID = "DUMMY_BUY_ORDER_ID_" + UUID.randomUUID().toString();
            } else {
                orderID = tradingApi.createOrder(market.getId(), OrderType.BUY, piecesToBuy, currentTicker.getAsk());
            }

            LOG.info(() -> market.getName() + " BUY Order sent successfully to exchange. ID: " + orderID);

            currentBuyOrder = new OrderState(orderID, OrderType.BUY, piecesToBuy, currentTicker.getAsk());
            strategyState = IntelligentStrategyState.WAIT_FOR_BUY;
        } else {
            LOG.info(() -> market.getName() + " BUY phase - The market gain needed to place a BUY order was not reached. Wait for the next trading strategy tick.");
        }
    }

    private boolean marketMovedUp() {
        BigDecimal currentPercentageGainNeededForBuy = intelligentLimitAdapter.getCurrentPercentageGainNeededForBuy();
        BigDecimal lowestAskPrice = calulateLowestAskPriceIn(intelligentLimitAdapter.getCurrentLowestPriceLookbackCount());
        BigDecimal cleanedMarketPrice = calulateLowestAskPriceIn(intelligentLimitAdapter.getCurrentTimesAboveLowestPriceNeeded());
        BigDecimal amountToMoveUp = lowestAskPrice.multiply(currentPercentageGainNeededForBuy);
        BigDecimal goalToReach = lowestAskPrice.add(amountToMoveUp);
        BigDecimal percentageChangeMarketToMinimum = getPercentageChange(currentTicker.getAsk(), lowestAskPrice);
        BigDecimal percentageChangeCleanedMarketToMinimum = getPercentageChange(cleanedMarketPrice, lowestAskPrice);
        LOG.info(() -> market.getName() + "\n" +
                "######### BUY ORDER STATISTICS #########\n" +
                " * Price needed: " + decimalFormat.format(goalToReach) + " " + market.getCounterCurrency() +
                "\n * Current ask price: " + decimalFormat.format(currentTicker.getAsk()) + " " + market.getCounterCurrency() +
                "\n * Current cleaned ask price (in 3 ticks): " + decimalFormat.format(cleanedMarketPrice) + " " + market.getCounterCurrency() +
                "\n * Minimum seen ask price (in 20 ticks): " + decimalFormat.format(lowestAskPrice) + " " + market.getCounterCurrency() +
                "\n * Gain needed from this minimum price: " + decimalFormat.format(currentPercentageGainNeededForBuy.multiply(oneHundred)) +
                "% = " + decimalFormat.format(amountToMoveUp) + " " + market.getCounterCurrency() +
                "\n * Current market above minimum: " + decimalFormat.format(percentageChangeMarketToMinimum) + "%" +
                "\n * Cleaned market above minimum (3 ticks): " + decimalFormat.format(percentageChangeCleanedMarketToMinimum) + "%\n" +
                "########################################");

        return cleanedMarketPrice.compareTo(goalToReach) > 0;
    }

    private BigDecimal calulateLowestAskPriceIn(int ticks) {
        int currentEndIndex = series.getEndIndex();
        Num result = series.getBar(currentEndIndex).getHighPrice();
        int currentBeginIndex = series.getBeginIndex();

        int spanStartIndex = currentEndIndex - ticks;
        int availableStartIndex = Math.max(currentBeginIndex, spanStartIndex);
        for(int i=availableStartIndex; i<currentEndIndex; i++) {
            result = series.getBar(i).getHighPrice().min(result);
        }
        return (BigDecimal)result.getDelegate();
    }

    private void updateMarketPrices() throws ExchangeNetworkException, TradingApiException {
        currentTicker = tradingApi.getTicker(market.getId());
        LOG.info(() -> market.getName() + " Updated latest market info: " + currentTicker);
        Long timestampInTicker = currentTicker.getTimestamp();
        ZonedDateTime tickerTimestamp;
        if(timestampInTicker == null) {
            tickerTimestamp = ZonedDateTime.now();
        } else {
            tickerTimestamp = ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestampInTicker), ZoneId.systemDefault());
        }
        series.addBar(tickerTimestamp, currentTicker.getLast(), currentTicker.getAsk(), currentTicker.getBid(), currentTicker.getLast());
    }

    private void executeSellPhase() throws TradingApiException, ExchangeNetworkException, StrategyException {
        LOG.info(() -> market.getName() + " SELL phase - create a SELL order for the last sucessfull BUY.");
        if (currentSellOrder != null) {
            String errorMsg = "The SELL phase is to be executed, but there is still an open SELL order '" + currentSellOrder + "'. This should never happen. Stop the bot!";
            LOG.error(() -> errorMsg);
            throw new StrategyException(errorMsg);
        }
        BigDecimal sellPrice = computeCurrentSellPrice();
        LOG.info(() -> market.getName() + " SELL phase - Place a SELL order of '" + decimalFormat.format(currentBuyOrder.getAmount()) + " * " + decimalFormat.format(sellPrice) + " " + market.getCounterCurrency() + "'");

        String orderId;

        if (debugModeEnabled) {
            orderId = "DUMMY_SELL_ORDER_ID_" + UUID.randomUUID().toString();
        } else {
            orderId = tradingApi.createOrder(market.getId(), OrderType.SELL, currentBuyOrder.getAmount(), sellPrice);
        }

        LOG.info(() -> market.getName() + " SELL Order sent successfully to exchange. ID: " + orderId);

        currentSellOrder = new OrderState(orderId, OrderType.SELL, currentBuyOrder.getAmount(), sellPrice);
        strategyState = IntelligentStrategyState.WAIT_FOR_SELL;
    }

    private BigDecimal computeCurrentSellPrice() throws TradingApiException, ExchangeNetworkException {
        BigDecimal breakEven = calculateBreakEven();
        LOG.info(() -> market.getName() + " The calculated break even for selling would be '" + decimalFormat.format(breakEven) + market.getCounterCurrency() + "'");
        BigDecimal maximalPriceLimitAboveBreakEven = calculateMaximalPriceLimitAboveBreakEven(breakEven);
        if (maximalPriceLimitAboveBreakEven.compareTo(breakEven) >= 0) {
            LOG.info(() -> market.getName() + " SELL phase - the above trailing gain is reached. Computed new SELL price is '" + decimalFormat.format(maximalPriceLimitAboveBreakEven) + " " + market.getCounterCurrency() + "' above the break even");
            return maximalPriceLimitAboveBreakEven;
        } else {
            BigDecimal belowBreakEvenPriceLimit = calculateBelowBreakEvenPriceLimit();
            LOG.info(() -> market.getName() + " SELL phase - still below break even. Computed new SELL price is '" + decimalFormat.format(belowBreakEvenPriceLimit) + " " + market.getCounterCurrency() + "' below the break even.");
            return belowBreakEvenPriceLimit;
        }
    }

    private BigDecimal calculateMaximalPriceLimitAboveBreakEven(BigDecimal breakEven) {
        BigDecimal aboveBreakEvenPriceLimit = calculateAboveBreakEvenPriceLimit();
        BigDecimal minimumAboveBreakEvenPriceLimit = calculateMinimumAboveBreakEvenPriceLimit(breakEven);
        return aboveBreakEvenPriceLimit.max(minimumAboveBreakEvenPriceLimit);
    }

    private BigDecimal calculateBelowBreakEvenPriceLimit() {
        BigDecimal distanceToCurrentMarketPrice = currentTicker.getBid().multiply(intelligentLimitAdapter.getCurrentSellStopLimitPercentageBelowBreakEven());
        return currentTicker.getBid().subtract(distanceToCurrentMarketPrice);
    }

    private BigDecimal calculateAboveBreakEvenPriceLimit() {
        return currentTicker.getBid().subtract(currentTicker.getBid().multiply(intelligentLimitAdapter.getCurrentSellStopLimitPercentageAboveBreakEven()));
    }

    private BigDecimal calculateMinimumAboveBreakEvenPriceLimit(BigDecimal breakEven) {
        BigDecimal currentSellStopLimitPercentageMinimumAboveBreakEven = intelligentLimitAdapter.getCurrentSellStopLimitPercentageMinimumAboveBreakEven();
        BigDecimal minimalDistanceToCurrentMarketPrice = currentTicker.getBid().subtract(currentTicker.getBid().multiply(currentSellStopLimitPercentageMinimumAboveBreakEven));
        BigDecimal minimalDistanceNeededToBreakEven = breakEven.add(breakEven.multiply(currentSellStopLimitPercentageMinimumAboveBreakEven));
        return minimalDistanceNeededToBreakEven.min(minimalDistanceToCurrentMarketPrice);

    }

    private BigDecimal calculateBreakEven() throws TradingApiException, ExchangeNetworkException {
        /* (p1 * (1+f)) / (1-f) <= p2 */
        BigDecimal buyFees = BigDecimal.ONE.add(tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(market.getId()));
        BigDecimal sellFees = BigDecimal.ONE.subtract(tradingApi.getPercentageOfSellOrderTakenForExchangeFee(market.getId()));

        BigDecimal totalBuy = currentBuyOrder.getPrice().multiply(buyFees);
        BigDecimal estimatedBreakEven = totalBuy.divide(sellFees, 8, RoundingMode.HALF_UP);
        return estimatedBreakEven;
    }

    private void computeInitialStrategyState() throws ExchangeNetworkException, TradingApiException, StrategyException {
        final List<OpenOrder> myOrders = tradingApi.getYourOpenOrders(market.getId());
        if (myOrders.isEmpty()) {
            LOG.info(() -> market.getName() + " No open orders found. Check available balance for the base currency, to know if a new sell order should be created.");
            final BigDecimal currentBaseCurrencyBalance = getAvailableCurrencyBalance(market.getBaseCurrency());
            if (currentBaseCurrencyBalance.compareTo(MINIMAL_ACCOUNT_BALANCE_FOR_RESUME_SELL) > 0) {
                LOG.info(() -> market.getName() + " Open balance in base currency found. Resume needed. Set current phase to SELL and use as BUY price the current market ask price");
                currentBuyOrder = new OrderState("DUMMY_STRATEGY_RESUMED_BUY_ORDER_DUE_TO_OPEN_BALANCE", OrderType.BUY, currentBaseCurrencyBalance, currentTicker.getAsk());
                strategyState = IntelligentStrategyState.NEED_SELL;
                return;
            } else {
                LOG.info(() -> market.getName() + " No significant open balance in base currency found (" + decimalFormat.format(currentBaseCurrencyBalance) + " " + market.getBaseCurrency() + "). No resume needed. Set current phase to BUY.");
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
            currentBuyOrder = new OrderState(currentOpenOrder.getId(), currentOpenOrder.getType(), currentOpenOrder.getOriginalQuantity(), currentOpenOrder.getPrice());
            strategyState = IntelligentStrategyState.WAIT_FOR_BUY;
        } else {
            LOG.info(() -> market.getName() + " The current order is a SELL order. Resume with waiting for SELL to be fulfilled or changing SELL prices.");
            BigDecimal estimatedBuyPrice = currentTicker.getAsk().max(currentOpenOrder.getPrice());
            currentBuyOrder = new OrderState("DUMMY_STRATEGY_RESUMED_BUY_ORDER_DUE_TO_OPEN_SELL_ORDER", OrderType.BUY, currentOpenOrder.getQuantity(), estimatedBuyPrice);
            currentSellOrder = new OrderState(currentOpenOrder.getId(), currentOpenOrder.getType(), currentOpenOrder.getQuantity(), currentOpenOrder.getPrice());
            strategyState = IntelligentStrategyState.WAIT_FOR_SELL;
        }
    }

    private BigDecimal getAmountOfPiecesToBuy() throws TradingApiException, ExchangeNetworkException, StrategyException {
        // TODO final BigDecimal balanceToUseForBuyOrder = getBalanceToUseForBuyOrder();
        final BigDecimal balanceToUseForBuyOrder = twentyFive;
        LOG.info(
                () ->
                        market.getName()
                                + " Calculating amount of base currency ("
                                + market.getBaseCurrency()
                                + ") to buy for amount of counter currency "
                                + decimalFormat.format(balanceToUseForBuyOrder)
                                + " "
                                + market.getCounterCurrency());

        /*
         * Most exchanges (if not all) use 8 decimal places and typically round in favour of the
         * exchange. It's usually safest to round down the order quantity in your calculations.
         */
        final BigDecimal amountOfPiecesInBaseCurrencyToBuy = balanceToUseForBuyOrder.divide(currentTicker.getAsk(), 8, RoundingMode.HALF_DOWN);

        LOG.info(
                () ->
                        market.getName()
                                + " Amount of base currency ("
                                + market.getBaseCurrency()
                                + ") to BUY for "
                                + decimalFormat.format(balanceToUseForBuyOrder)
                                + " "
                                + market.getCounterCurrency()
                                + " based on last market trade price: "
                                + amountOfPiecesInBaseCurrencyToBuy);

        return amountOfPiecesInBaseCurrencyToBuy;
    }

    private BigDecimal getBalanceToUseForBuyOrder() throws ExchangeNetworkException, TradingApiException, StrategyException {
        final BigDecimal currentBalance = getAvailableCurrencyBalance(market.getCounterCurrency());

        BigDecimal balanceAvailableForTrading = currentBalance.subtract(configuredEmergencyStop);
        if (balanceAvailableForTrading.compareTo(BigDecimal.ZERO) <= 0) {
            String errorMsg = "No balance available for trading. When substracting the emergency stop, the remaining balance is " + decimalFormat.format(balanceAvailableForTrading) + " " + market.getCounterCurrency();
            LOG.error(() -> market.getName() + errorMsg);
            throw new StrategyException(errorMsg);
        }
        LOG.info(() -> market.getName() + "Balance available after being reduced by the emergency stop: " + decimalFormat.format(balanceAvailableForTrading) + " " + market.getCounterCurrency());
        BigDecimal balanceToUseForBuyOrder = balanceAvailableForTrading.multiply(configuredPercentageOfCounterCurrencyBalanceToUse);
        LOG.info(() -> market.getName() + "Balance to be used for trading, taking into consideration the configured trading percentage of "
                + decimalFormat.format(configuredPercentageOfCounterCurrencyBalanceToUse) + ": " + decimalFormat.format(balanceToUseForBuyOrder) + " " + market.getCounterCurrency());
        return balanceToUseForBuyOrder;
    }

    private BigDecimal getAvailableCurrencyBalance(String currency) throws ExchangeNetworkException, TradingApiException, StrategyException {
        LOG.info(() -> market.getName() + " Fetching the available balance for the currency '" + currency + "'.");
        BalanceInfo balanceInfo = tradingApi.getBalanceInfo();
        final BigDecimal currentBalance = balanceInfo.getBalancesAvailable().get(currency);
        if (currentBalance == null) {
            final String errorMsg = "Failed to get current currency balance as '" + currency + "' key is not available in the balances map. Balances returned: " + balanceInfo.getBalancesAvailable();
            LOG.warn(() -> errorMsg);
            return BigDecimal.ZERO;
        } else {
            LOG.info(() -> market.getName() + "Currency balance available on exchange is ["
                    + decimalFormat.format(currentBalance)
                    + "] "
                    + currency);
        }
        return currentBalance;
    }

    private void getConfigForStrategy(StrategyConfig config) {
        configuredPercentageOfCounterCurrencyBalanceToUse = StrategyConfigParser.readPercentageConfigValue(config, "percentage-of-counter-currency-balance-to-use");
        configuredEmergencyStop = StrategyConfigParser.readAmount(config, "configured-emergency-stop-balance");
        debugModeEnabled = StrategyConfigParser.readBoolean(config, "debug-mode-enabled", false);
    }
}
