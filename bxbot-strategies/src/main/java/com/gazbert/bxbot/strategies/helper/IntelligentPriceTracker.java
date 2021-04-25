package com.gazbert.bxbot.strategies.helper;

import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.trading.api.*;
import com.google.common.base.Predicates;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ta4j.core.BarSeries;

import javax.xml.datatype.Duration;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

public class IntelligentPriceTracker {

    private static final Logger LOG = LogManager.getLogger();

    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#.########");

    private final TradingApi tradingApi;
    private final Market market;
    private final BarSeries series;
    private final Map<Integer, Map<String, BigDecimal>> balances = new HashMap<>();

    public IntelligentPriceTracker(TradingApi tradingApi, Market market, BarSeries series) {
        this.tradingApi = tradingApi;
        this.market = market;
        this.series = series;
    }


    public void updateMarketPrices() throws ExchangeNetworkException, TradingApiException {
        Ticker currentTicker = tradingApi.getTicker(market.getId());
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

    public BigDecimal getAvailableCounterCurrencyBalance() throws ExchangeNetworkException, TradingApiException {
        return getBalance(market.getCounterCurrency());
    }

    public BigDecimal getAvailableBaseCurrencyBalance() throws ExchangeNetworkException, TradingApiException {
        return getBalance(market.getBaseCurrency());
    }

    private BigDecimal getBalance(String currency) throws ExchangeNetworkException, TradingApiException {
        if(!balances.containsKey(getCurrentTick())) {
            loadAvailableBalancesFromServer();
        }
        Map<String, BigDecimal> currentAccountBalances = balances.get(getCurrentTick());
        final BigDecimal currentBalance = currentAccountBalances.get(currency);
        if (currentBalance == null) {
            final String errorMsg = "Failed to get current currency balance as '" + currency + "' key is not available in the balances map. Balances available: " + currentAccountBalances;
            LOG.warn(() -> errorMsg);
            return BigDecimal.ZERO;
        }
        LOG.info(() -> market.getName() + "Currency balance available on exchange is ["+formatWithCurrency(currentBalance, currency) + "]");
        return currentBalance;
    }


    private void loadAvailableBalancesFromServer() throws ExchangeNetworkException, TradingApiException {
        LOG.info(() -> market.getName() + " Fetching all available balances from the exchange.");
        BalanceInfo balanceInfo = tradingApi.getBalanceInfo();
        Map<String, BigDecimal> loadedBalances = balanceInfo.getBalancesAvailable();
        LOG.info(() -> market.getName() + " Loaded the following account balances from the exchange: " + loadedBalances);
        balances.put(getCurrentTick(), loadedBalances);
    }

    private int getCurrentTick() {
        return series.getEndIndex();
    }

    public String getFormattedBaseCurrencyBalance() throws ExchangeNetworkException, TradingApiException {
        return formatWithBaseCurrency(getAvailableBaseCurrencyBalance());
    }

    public String getFormattedCounterCurrencyBalance() throws ExchangeNetworkException, TradingApiException {
        return formatWithBaseCurrency(getAvailableCounterCurrencyBalance());
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
        return DECIMAL_FORMAT.format(amount) + " " + currency;
    }

    public BarSeries getSeries() {
        return series;
    }
}
