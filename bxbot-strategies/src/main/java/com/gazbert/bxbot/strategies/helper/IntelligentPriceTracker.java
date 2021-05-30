package com.gazbert.bxbot.strategies.helper;

import com.gazbert.bxbot.strategies.StrategyConfigParser;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.trading.api.BalanceInfo;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.Market;
import com.gazbert.bxbot.trading.api.Ohlc;
import com.gazbert.bxbot.trading.api.OhlcFrame;
import com.gazbert.bxbot.trading.api.OhlcInterval;
import com.gazbert.bxbot.trading.api.TradingApi;
import com.gazbert.bxbot.trading.api.TradingApiException;
import com.gazbert.bxbot.trading.api.util.ta4j.MarketEnterType;
import com.gazbert.bxbot.trading.api.util.ta4j.Ta4j2Chart;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;

public class IntelligentPriceTracker {

    private static final Logger LOG = LogManager.getLogger();

    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#.########");

    private static final int MAX_AMOUNT_LIVECHART_BARS = 250;


    private final TradingApi tradingApi;
    private final Market market;
    private final BarSeries series;
    private static final Map<Long, Map<String, BigDecimal>> balances =
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
    private long currentTick;
    private static long OVERALL_HIGHEST_TICK = 0;

    public IntelligentPriceTracker(TradingApi tradingApi, Market market, StrategyConfig config) {
        this.tradingApi = tradingApi;
        this.market = market;
        this.series =
                new BaseBarSeriesBuilder()
                        .withName(market.getName() + "_" + System.currentTimeMillis())
                        .build();
        this.shouldShowLiveChart = StrategyConfigParser.readBoolean(config, "show-live-chart", false);
    }

    public void updateMarketPrices() throws ExchangeNetworkException, TradingApiException, StrategyException {
        safelyeUpdateCurrentTick();
        Ohlc ohlcData = tradingApi.getOhlc(market.getId(), OhlcInterval.FiveMinutes, resumeID);
        LOG.info(() -> market.getName() + " Updated latest market info: " + ohlcData);

        for (int i = 0; i < ohlcData.getFrames().size(); i++) {
            OhlcFrame frame = ohlcData.getFrames().get(i);
            ZonedDateTime startTime = frame.getTime();
            ZonedDateTime endTime = startTime.plusMinutes(5);
            Duration between = Duration.between(frame.getTime(), endTime);

            Bar newBar = new BaseBar(
                    between,
                    endTime,
                    frame.getOpen(),
                    frame.getHigh(),
                    frame.getLow(),
                    frame.getClose(),
                    frame.getVolume());

            boolean replaceLastBar = resumeID != null && i == 0;
            series.addBar(newBar, replaceLastBar);
        }

        resumeID = ohlcData.getResumeID();
        this.updateLiveGraph();
    }

    private void safelyeUpdateCurrentTick() {
        currentTick++;
        if(currentTick > OVERALL_HIGHEST_TICK) {
            if (currentTick -1 == OVERALL_HIGHEST_TICK) {
                OVERALL_HIGHEST_TICK++;
            } else {
                throw new IllegalStateException("The current strategy tick '" + currentTick + "' is far beyond the overall highest tick '" + OVERALL_HIGHEST_TICK + "'.");
            }
        }

        if(currentTick<OVERALL_HIGHEST_TICK) {
            // one of the executed strategies crashed so that this Stratetgy instace did not get executed. Catch up with the counter to get valid balance and order results
            currentTick = OVERALL_HIGHEST_TICK;
        }
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
        if (!balances.containsKey(currentTick)) {
            loadAvailableBalancesFromServer();
        }
        Map<String, BigDecimal> currentAccountBalances = balances.get(currentTick);
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
        balances.put(currentTick, loadedBalances);
    }

    public long getCurrentStrategyTick() {
        return currentTick;
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

    public void adaptBalanceDueToBuyEvent(BigDecimal amount, BigDecimal price, MarketEnterType marketEnterType) throws TradingApiException, ExchangeNetworkException {
        if (marketEnterType == MarketEnterType.SHORT_POSITION) {
            return;
        }
        Map<String, BigDecimal> currentAccountBalances = balances.get(currentTick);
        BigDecimal availableBalanceBeforeBuy = currentAccountBalances.get(market.getCounterCurrency());
        BigDecimal orderPrice = amount.multiply(price);
        BigDecimal buyFees = tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(market.getId()).multiply(orderPrice);
        BigDecimal netOrderPrice = orderPrice.add(buyFees);
        BigDecimal availableBalancesAfterBuy = availableBalanceBeforeBuy.subtract(netOrderPrice);
        currentAccountBalances.put(market.getCounterCurrency(), availableBalancesAfterBuy);
    }
}
