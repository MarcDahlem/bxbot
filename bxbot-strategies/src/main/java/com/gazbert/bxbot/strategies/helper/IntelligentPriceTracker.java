package com.gazbert.bxbot.strategies.helper;

import com.gazbert.bxbot.strategies.StrategyConfigParser;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.trading.api.*;
import com.gazbert.bxbot.trading.api.util.ta4j.Ta4j2Chart;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.Indicator;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

public class IntelligentPriceTracker {

    private static final Logger LOG = LogManager.getLogger();

    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#.########");

    private static final int MAX_AMOUNT_LIVECHART_BARS = 1500;

    private final TradingApi tradingApi;
    private final Market market;
    private final BarSeries series;
    private static final Map<Integer, Map<String, BigDecimal>> balances =
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(final Map.Entry eldest) {
                    return size() > 10;
                }
            };
    private final boolean shouldShowLiveChart;

    private String liveGraphID;
    private final Collection<Ta4j2Chart.ChartIndicatorConfig> registeredLiveChartIndicatorConfigs =
            new LinkedList<>();
    private Integer resumeID = null;

    public IntelligentPriceTracker(TradingApi tradingApi, Market market, StrategyConfig config) {
        this.tradingApi = tradingApi;
        this.market = market;
        this.series =
                new BaseBarSeriesBuilder()
                        .withName(market.getName() + "_" + System.currentTimeMillis())
                        .build();
        this.shouldShowLiveChart = StrategyConfigParser.readBoolean(config, "show-live-chart", false);
    }

    public void updateMarketPrices() throws ExchangeNetworkException, TradingApiException {
        Ohlc ohlcData = tradingApi.getOhlc(market.getId(), OhlcInterval.OneMinute, resumeID);
        LOG.info(() -> market.getName() + " Updated latest market info: " + ohlcData);
        resumeID = ohlcData.getResumeID();
        for (OhlcFrame frame : ohlcData.getFrames()) {
            if (isCompleted(frame)) {
                ZonedDateTime startTime = frame.getTime();
                ZonedDateTime endTime = startTime.plusMinutes(1);

                series.addBar(
                        Duration.between(startTime, endTime),
                        endTime,
                        frame.getOpen(),
                        frame.getHigh(),
                        frame.getLow(),
                        frame.getClose(),
                        frame.getVolume());
            }
        }

        this.updateLiveGraph();
    }

    private boolean isCompleted(OhlcFrame frame) {
        return frame.getTime().toEpochSecond() <= resumeID;
    }

    public BigDecimal getAsk() {
        return (BigDecimal) series.getLastBar().getHighPrice().getDelegate();
    }

    public String getFormattedAsk() {
        return formatWithCounterCurrency(getAsk());
    }

    public BigDecimal getBid() {
        return (BigDecimal) series.getLastBar().getLowPrice().getDelegate();
    }

    public String getFormattedBid() {
        return formatWithCounterCurrency(getBid());
    }

    public BigDecimal getLast() {
        return (BigDecimal) series.getLastBar().getClosePrice().getDelegate();
    }

    public String getFormattedLast() {
        return formatWithCounterCurrency(getLast());
    }

    public BigDecimal getAvailableCounterCurrencyBalance()
            throws ExchangeNetworkException, TradingApiException {
        return getBalance(market.getCounterCurrency());
    }

    public BigDecimal getAvailableBaseCurrencyBalance()
            throws ExchangeNetworkException, TradingApiException {
        return getBalance(market.getBaseCurrency());
    }

    private BigDecimal getBalance(String currency)
            throws ExchangeNetworkException, TradingApiException {
        if (!balances.containsKey(getCurrentTick())) {
            loadAvailableBalancesFromServer();
        }
        Map<String, BigDecimal> currentAccountBalances = balances.get(getCurrentTick());
        final BigDecimal currentBalance = currentAccountBalances.get(currency);
        if (currentBalance == null) {
            final String errorMsg =
                    "Failed to get current currency balance as '"
                            + currency
                            + "' key is not available in the balances map. Balances available: "
                            + currentAccountBalances;
            LOG.warn(() -> errorMsg);
            return BigDecimal.ZERO;
        }
        LOG.info(
                () ->
                        market.getName()
                                + "Currency balance available on exchange is ["
                                + formatWithCurrency(currentBalance, currency)
                                + "]");
        return currentBalance;
    }

    private void loadAvailableBalancesFromServer()
            throws ExchangeNetworkException, TradingApiException {
        LOG.info(() -> market.getName() + " Fetching all available balances from the exchange.");
        BalanceInfo balanceInfo = tradingApi.getBalanceInfo();
        Map<String, BigDecimal> loadedBalances = balanceInfo.getBalancesAvailable();
        LOG.info(
                () ->
                        market.getName()
                                + " Loaded the following account balances from the exchange: "
                                + loadedBalances);
        balances.put(getCurrentTick(), loadedBalances);
    }

    public int getCurrentTick() {
        return series.getEndIndex();
    }

    public String getFormattedBaseCurrencyBalance()
            throws ExchangeNetworkException, TradingApiException {
        return formatWithBaseCurrency(getAvailableBaseCurrencyBalance());
    }

    public String getFormattedCounterCurrencyBalance()
            throws ExchangeNetworkException, TradingApiException {
        return formatWithCounterCurrency(getAvailableCounterCurrencyBalance());
    }

    public String formatWithCounterCurrency(BigDecimal amount) {
        String counterCurrency = market.getCounterCurrency();
        return formatWithCurrency(amount, counterCurrency);
    }

    public String formatWithBaseCurrency(BigDecimal amount) {
        String baseCurrency = market.getBaseCurrency();
        return formatWithCurrency(amount, baseCurrency);
    }

    private String formatWithCurrency(BigDecimal amount, String currency) {
        if (amount == null) {
            return "<NaN> " + currency;
        }
        return DECIMAL_FORMAT.format(amount) + " " + currency;
    }

    public BarSeries getSeries() {
        return series;
    }

    public void addLivechartIndicatorConfig(Ta4j2Chart.ChartIndicatorConfig indicatorConfig) {
        this.registeredLiveChartIndicatorConfigs.add(indicatorConfig);
    }

    private void updateLiveGraph() {
        if (shouldShowLiveChart) {
            if (liveGraphID == null) {
                liveGraphID =
                        Ta4j2Chart.createLiveChart(
                                getSeries(), getLivechartIndicatorConfigs(), MAX_AMOUNT_LIVECHART_BARS);
            } else {
                Ta4j2Chart.updateLiveChart(liveGraphID);
            }
        }
    }

    private Collection<Ta4j2Chart.ChartIndicatorConfig> getLivechartIndicatorConfigs() {
        return new LinkedList<>(this.registeredLiveChartIndicatorConfigs);
    }
}
