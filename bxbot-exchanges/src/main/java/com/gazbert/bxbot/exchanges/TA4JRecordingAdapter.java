package com.gazbert.bxbot.exchanges;

import com.gazbert.bxbot.exchange.api.ExchangeAdapter;
import com.gazbert.bxbot.exchange.api.ExchangeConfig;
import com.gazbert.bxbot.exchange.api.OtherConfig;
import com.gazbert.bxbot.exchanges.ta4jhelper.TradePriceRespectingBacktestExecutor;
import com.gazbert.bxbot.exchanges.trading.api.impl.BalanceInfoImpl;
import com.gazbert.bxbot.exchanges.trading.api.impl.OpenOrderImpl;
import com.gazbert.bxbot.exchanges.trading.api.impl.TickerImpl;
import com.gazbert.bxbot.trading.api.*;
import com.gazbert.bxbot.trading.api.util.JsonBarsSerializer;
import com.gazbert.bxbot.trading.api.util.ta4j.SellIndicator;
import com.gazbert.bxbot.trading.api.util.ta4j.Ta4j2Chart;
import com.gazbert.bxbot.trading.api.util.ta4j.RecordedStrategy;
import com.gazbert.bxbot.trading.api.util.ta4j.Ta4jOptimalTradingStrategy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ta4j.core.*;
import org.ta4j.core.cost.LinearTransactionCostModel;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.reports.PerformanceReport;
import org.ta4j.core.reports.PositionStatsReport;
import org.ta4j.core.reports.TradingStatement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

public class TA4JRecordingAdapter extends AbstractExchangeAdapter implements ExchangeAdapter {
    private static final Logger LOG = LogManager.getLogger();
    private static final String ORDER_FEE_PROPERTY_NAME = "order-fee";
    private static final String SIMULATED_COUNTER_CURRENCY_PROPERTY_NAME = "simulatedCounterCurrency";
    private static final String COUNTER_CURRENCY_START_BALANCE_PROPERTY_NAME = "counterCurrencyStartingBalance";
    private static final String SIMULATED_BASE_CURRENCY_PROPERTY_NAME = "simulatedBaseCurrency";
    private static final String BASE_CURRENCY_START_BALANCE_PROPERTY_NAME = "baseCurrencyStartingBalance";
    private static final String PATH_TO_SERIES_JSON_PROPERTY_NAME = "trading-series-json-path";
    private static final String SHOULD_GENERATE_CHARTS_PROPERTY_NAME = "generate-order-overview-charts";


    private BigDecimal orderFeePercentage;
    private String tradingSeriesTradingPath;
    private String simulatedCounterCurrency;
    private String simulatedBaseCurrency;
    private boolean shouldPrintCharts;

    private BarSeries tradingSeries;

    private BigDecimal baseCurrencyBalance;
    private BigDecimal counterCurrencyBalance;
    private OpenOrder currentOpenOrder;
    private int currentTick;
    private SellIndicator recordingIndicator;

    @Override
    public void init(ExchangeConfig config) {
        LOG.info(() -> "About to initialise ta4j recording adapter with the following exchange config: " + config);
        setOtherConfig(config);
        loadRecodingSeriesFromJson();
        currentTick = tradingSeries.getBeginIndex() - 1;
    }

    @Override
    public String getImplName() {
        return "ta4j recording and analyzing adapter";
    }

    @Override
    public MarketOrderBook getMarketOrders(String marketId) throws ExchangeNetworkException, TradingApiException {
        throw new TradingApiException("get market orders is not implemented", new UnsupportedOperationException());
    }

    @Override
    public List<OpenOrder> getYourOpenOrders(String marketId) throws ExchangeNetworkException, TradingApiException {
        LinkedList<OpenOrder> result = new LinkedList<>();
        if (currentOpenOrder != null) {
            result.add(currentOpenOrder);
            LOG.info(() -> "Found an open DUMMY order: " + currentOpenOrder);
        } else {
            LOG.info(() -> "no open order found. Return empty order list");
        }
        return result;
    }

    @Override
    public String createOrder(String marketId, OrderType orderType, BigDecimal quantity, BigDecimal price) throws ExchangeNetworkException, TradingApiException {
        if (currentOpenOrder != null) {
            throw new TradingApiException("Can only record/execute one order at a time. Wait for the open order to fulfill");
        }
        String newOrderID = "DUMMY_" + orderType + "_ORDER_ID_" + System.currentTimeMillis();
        Date creationDate = Date.from(tradingSeries.getBar(currentTick).getEndTime().toInstant());
        BigDecimal total = price.multiply(quantity);
        currentOpenOrder = new OpenOrderImpl(newOrderID, creationDate, marketId, orderType, price, quantity, quantity, total);
        LOG.info(() -> "Created a new dummy order: " + currentOpenOrder);
        checkOpenOrderExecution(marketId);
        return newOrderID;
    }

    @Override
    public boolean cancelOrder(String orderId, String marketId) throws ExchangeNetworkException, TradingApiException {
        if (currentOpenOrder == null) {
            throw new TradingApiException("Tried to cancel a order, but no open order found");
        }
        if (!currentOpenOrder.getId().equals(orderId)) {
            throw new TradingApiException("Tried to cancel a order, but the order id does not match the current open order. Expected: " + currentOpenOrder.getId() + ", actual: " + orderId);
        }
        LOG.info(() -> "The following order is canceled: " + currentOpenOrder);
        currentOpenOrder = null;
        return true;
    }

    @Override
    public BigDecimal getLatestMarketPrice(String marketId) throws ExchangeNetworkException, TradingApiException {
        return (BigDecimal) tradingSeries.getBar(currentTick).getClosePrice().getDelegate();
    }

    @Override
    public BalanceInfo getBalanceInfo() throws ExchangeNetworkException, TradingApiException {
        HashMap<String, BigDecimal> availableBalances = new HashMap<>();
        availableBalances.put(simulatedBaseCurrency, baseCurrencyBalance);
        availableBalances.put(simulatedCounterCurrency, counterCurrencyBalance);
        BalanceInfoImpl currentBalance = new BalanceInfoImpl(availableBalances, new HashMap<>());
        LOG.info(() -> "Return the following simulated balances: " + currentBalance);
        return currentBalance;
    }

    @Override
    public Ticker getTicker(String marketId) throws TradingApiException, ExchangeNetworkException {
        currentTick++;
        LOG.info("Tick increased to '" + currentTick + "'");
        if (currentTick > tradingSeries.getEndIndex()) {
            finishRecording(marketId);
            return null;
        }

        checkOpenOrderExecution(marketId);

        Bar currentBar = tradingSeries.getBar(currentTick);
        BigDecimal last = (BigDecimal) currentBar.getClosePrice().getDelegate();
        BigDecimal bid = (BigDecimal) currentBar.getLowPrice().getDelegate(); // assumes that the stored series json contains the bid price in the low price property
        BigDecimal ask = (BigDecimal) currentBar.getHighPrice().getDelegate(); // assumes that the stored series json contains the ask price in the high price property
        BigDecimal low = (BigDecimal) currentBar.getLowPrice().getDelegate();
        BigDecimal high = (BigDecimal) currentBar.getHighPrice().getDelegate();
        BigDecimal open = (BigDecimal) currentBar.getOpenPrice().getDelegate();
        BigDecimal volume = (BigDecimal) currentBar.getVolume().getDelegate();
        BigDecimal vwap = BigDecimal.ZERO;
        Long timestamp = currentBar.getEndTime().toInstant().toEpochMilli();
        return new TickerImpl(last, bid, ask, low, high, open, volume, vwap, timestamp);
    }

    private void loadRecodingSeriesFromJson() {
        tradingSeries = JsonBarsSerializer.loadSeries(tradingSeriesTradingPath);
        if (tradingSeries == null || tradingSeries.isEmpty()) {
            throw new IllegalArgumentException("Could not load ta4j series from json '" + tradingSeriesTradingPath + "'");
        }
    }

    private void setOtherConfig(ExchangeConfig exchangeConfig) {
        LOG.info(() -> "Load ta4j adapter config...");
        final OtherConfig otherConfig = getOtherConfig(exchangeConfig);

        final String orderFeeInConfig = getOtherConfigItem(otherConfig, ORDER_FEE_PROPERTY_NAME);
        orderFeePercentage =
                new BigDecimal(orderFeeInConfig).divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP);
        LOG.info(() -> "Order fee % in BigDecimal format: " + orderFeePercentage);

        tradingSeriesTradingPath = getOtherConfigItem(otherConfig, PATH_TO_SERIES_JSON_PROPERTY_NAME);
        LOG.info(() -> "path to load series json from for recording:" + tradingSeriesTradingPath);

        simulatedBaseCurrency = getOtherConfigItem(otherConfig, SIMULATED_BASE_CURRENCY_PROPERTY_NAME);
        LOG.info(() -> "Base currency to be simulated:" + simulatedBaseCurrency);

        final String startingBaseBalanceInConfig = getOtherConfigItem(otherConfig, BASE_CURRENCY_START_BALANCE_PROPERTY_NAME);
        baseCurrencyBalance = new BigDecimal(startingBaseBalanceInConfig);
        LOG.info(() -> "Base currency balance at simulation start in BigDecimal format: " + baseCurrencyBalance);

        simulatedCounterCurrency = getOtherConfigItem(otherConfig, SIMULATED_COUNTER_CURRENCY_PROPERTY_NAME);
        LOG.info(() -> "Counter currency to be simulated:" + simulatedCounterCurrency);

        final String startingBalanceInConfig = getOtherConfigItem(otherConfig, COUNTER_CURRENCY_START_BALANCE_PROPERTY_NAME);
        counterCurrencyBalance = new BigDecimal(startingBalanceInConfig);
        LOG.info(() -> "Counter currency balance at simulation start in BigDecimal format: " + counterCurrencyBalance);

        final String shouldGenerateChartsInConfig = getOtherConfigItem(otherConfig, SHOULD_GENERATE_CHARTS_PROPERTY_NAME);
        shouldPrintCharts = Boolean.parseBoolean(shouldGenerateChartsInConfig);
        LOG.info(() -> "Should print charts at simulation end: " + shouldPrintCharts);
    }

    private void checkOpenOrderExecution(String marketId) throws TradingApiException {
        if (currentOpenOrder != null) {
            switch (currentOpenOrder.getType()) {
                case BUY:
                    checkOpenBuyOrderExecution(marketId);
                    break;
                case SELL:
                    checkOpenSellOrderExecution(marketId);
                    break;
                default:
                    throw new TradingApiException("Order type not recognized: " + currentOpenOrder.getType());
            }
        }
    }

    private void checkOpenSellOrderExecution(String marketId) {
        BigDecimal currentBidPrice = (BigDecimal) tradingSeries.getBar(currentTick).getLowPrice().getDelegate(); // assumes that the stored series json contains the bid price in the low price property
        if (currentBidPrice.compareTo(currentOpenOrder.getPrice()) <= 0) {
            LOG.info("SELL: the market's bid price moved below the stop-limit price --> record sell order execution with the current bid price");
            getRecordingIndicator(marketId).registerSellOrderExecution(currentTick);
            BigDecimal orderPrice = currentOpenOrder.getOriginalQuantity().multiply(currentBidPrice);
            BigDecimal buyFees = getPercentageOfSellOrderTakenForExchangeFee(marketId).multiply(orderPrice);
            BigDecimal netOrderPrice = orderPrice.subtract(buyFees);
            counterCurrencyBalance = counterCurrencyBalance.add(netOrderPrice);
            baseCurrencyBalance = baseCurrencyBalance.subtract(currentOpenOrder.getOriginalQuantity());
            currentOpenOrder = null;
        }
    }

    private void checkOpenBuyOrderExecution(String marketId) {
        BigDecimal currentAskPrice = (BigDecimal) tradingSeries.getBar(currentTick).getHighPrice().getDelegate(); // assumes that the stored series json contains the ask price in the high price property
        if (currentAskPrice.compareTo(currentOpenOrder.getPrice()) <= 0) {
            LOG.info("BUY: the market's current ask price moved below the limit price --> record buy order execution with the current ask price");
            getRecordingIndicator(marketId).registerBuyOrderExecution(currentTick);
            BigDecimal orderPrice = currentOpenOrder.getOriginalQuantity().multiply(currentAskPrice);
            BigDecimal buyFees = getPercentageOfBuyOrderTakenForExchangeFee(marketId).multiply(orderPrice);
            BigDecimal netOrderPrice = orderPrice.add(buyFees);
            counterCurrencyBalance = counterCurrencyBalance.subtract(netOrderPrice);
            baseCurrencyBalance = baseCurrencyBalance.add(currentOpenOrder.getOriginalQuantity());
            currentOpenOrder = null;
        }
    }

    private SellIndicator getRecordingIndicator(String marketId) {
        if (recordingIndicator == null) {
            recordingIndicator = SellIndicator.createBreakEvenIndicator(tradingSeries, getPercentageOfBuyOrderTakenForExchangeFee(marketId), getPercentageOfSellOrderTakenForExchangeFee(marketId));
        }
        return recordingIndicator;
    }


    private void finishRecording(String marketId) throws TradingApiException, ExchangeNetworkException {
        final List<Strategy> strategies = new ArrayList<>();
        RecordedStrategy strategy = RecordedStrategy.createStrategyFromRecording("Recorded ta4j trades", getRecordingIndicator(marketId));
        strategies.add(strategy);

        RecordedStrategy optimalTradingStrategy = Ta4jOptimalTradingStrategy.createOptimalTradingStrategy(tradingSeries, getPercentageOfBuyOrderTakenForExchangeFee(marketId), getPercentageOfSellOrderTakenForExchangeFee(marketId));
        strategies.add(optimalTradingStrategy);

        TradePriceRespectingBacktestExecutor backtestExecutor = new TradePriceRespectingBacktestExecutor(tradingSeries, new LinearTransactionCostModel(getPercentageOfBuyOrderTakenForExchangeFee(marketId).doubleValue()));
        List<TradingStatement> statements = backtestExecutor.execute(strategies, tradingSeries.numOf(25), Trade.TradeType.BUY);
        logReports(statements);
        if (shouldPrintCharts) {
            Ta4j2Chart.printSeries(tradingSeries, strategy, strategy.createChartIndicators());
            Ta4j2Chart.printSeries(tradingSeries, optimalTradingStrategy, optimalTradingStrategy.createChartIndicators());
        }
        throw new TradingApiException("Simulation end finished. Ending balance: " + getBalanceInfo());
    }

    private void logReports(List<TradingStatement> statements) {
        for (TradingStatement statement : statements) {
            LOG.info(() ->
                    "\n######### " + statement.getStrategy().getName() + " #########\n" +
                            createPerformanceReport(statement) + "\n" +
                            createTradesReport(statement) + "\n" +
                            "###########################"
            );
        }
    }

    private String createTradesReport(TradingStatement statement) {
        PositionStatsReport statsReport = statement.getPositionStatsReport();
        return "--------- trade statistics report ---------\n" +
                "loss trade count: " + statsReport.getLossCount() + "\n" +
                "profit trade count: " + statsReport.getProfitCount() + "\n" +
                "break even trade count: " + statsReport.getBreakEvenCount() + "\n" +
                "---------------------------";
    }

    private String createPerformanceReport(TradingStatement statement) {
        PerformanceReport performanceReport = statement.getPerformanceReport();
        return "--------- performance report ---------\n" +
                "total loss: " + performanceReport.getTotalLoss() + "\n" +
                "total profit: " + performanceReport.getTotalProfit() + "\n" +
                "total profit loss: " + performanceReport.getTotalProfitLoss() + "\n" +
                "total profit loss percentage: " + performanceReport.getTotalProfitLossPercentage() + "\n" +
                "---------------------------";
    }

    @Override
    public BigDecimal getPercentageOfBuyOrderTakenForExchangeFee(String marketId) {
        return orderFeePercentage;
    }

    @Override
    public BigDecimal getPercentageOfSellOrderTakenForExchangeFee(String marketId) {
        return orderFeePercentage;
    }

    public int getMaxIndex() {
        return tradingSeries.getEndIndex();
    }
}
