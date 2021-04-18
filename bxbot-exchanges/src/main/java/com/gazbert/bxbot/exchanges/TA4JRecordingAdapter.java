package com.gazbert.bxbot.exchanges;

import com.gazbert.bxbot.exchange.api.ExchangeAdapter;
import com.gazbert.bxbot.exchange.api.ExchangeConfig;
import com.gazbert.bxbot.exchange.api.OtherConfig;
import com.gazbert.bxbot.trading.api.util.JsonBarsSerializer;
import com.gazbert.bxbot.trading.api.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ta4j.core.BarSeries;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public class TA4JRecordingAdapter extends AbstractExchangeAdapter implements ExchangeAdapter {
    private static final Logger LOG = LogManager.getLogger();
    private static final String BUY_FEE_PROPERTY_NAME = "buy-fee";
    private static final String SELL_FEE_PROPERTY_NAME = "sell-fee";


    private BigDecimal buyFeePercentage;
    private BigDecimal sellFeePercentage;
    private BigDecimal sellLimitDistancePercentage;
    private String tradingSeriesTradingPath;


    private BarSeries tradingSeries;

    @Override
    public void init(ExchangeConfig config) {
        LOG.info(() -> "About to initialise ta4j recording ExchangeConfig: " + config);
        setOtherConfig(config);
        loadRecodingSeriesFromJson();
    }

    private void loadRecodingSeriesFromJson() {
        tradingSeries = JsonBarsSerializer.loadSeries(tradingSeriesTradingPath);
    }

    private void setOtherConfig(ExchangeConfig exchangeConfig) {
        final OtherConfig otherConfig = getOtherConfig(exchangeConfig);

        final String buyFeeInConfig = getOtherConfigItem(otherConfig, BUY_FEE_PROPERTY_NAME);
        buyFeePercentage =
                new BigDecimal(buyFeeInConfig).divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP);
        LOG.info(() -> "Buy fee % in BigDecimal format: " + buyFeePercentage);

        final String sellFeeInConfig = getOtherConfigItem(otherConfig, SELL_FEE_PROPERTY_NAME);
        sellFeePercentage =
                new BigDecimal(sellFeeInConfig).divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP);
        LOG.info(() -> "Sell fee % in BigDecimal format: " + sellFeePercentage);

        final String sellLimitDistanceInConfig = getOtherConfigItem(otherConfig, "sell-stop-limit-percentage-distance");
        sellLimitDistancePercentage =
                new BigDecimal(sellLimitDistanceInConfig).divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP);
        LOG.info(() -> "Sell (stop-limit order) limit distance % in BigDecimal format: " + sellLimitDistancePercentage);

        tradingSeriesTradingPath = getOtherConfigItem(otherConfig, "trading-series-json-path");
        LOG.info(() -> "path to load series json from for recording:" + tradingSeriesTradingPath);
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
        return null;
        //todo
    }

    @Override
    public String createOrder(String marketId, OrderType orderType, BigDecimal quantity, BigDecimal price) throws ExchangeNetworkException, TradingApiException {
        return null;
        //todo
    }

    @Override
    public boolean cancelOrder(String orderId, String marketId) throws ExchangeNetworkException, TradingApiException {
        return false;
        //todo
    }

    @Override
    public BigDecimal getLatestMarketPrice(String marketId) throws ExchangeNetworkException, TradingApiException {
        return null;
    }

    @Override
    public BalanceInfo getBalanceInfo() throws ExchangeNetworkException, TradingApiException {
        return null;
        // todo
    }

    @Override
    public Ticker getTicker(String marketId) throws TradingApiException, ExchangeNetworkException {
        return null;
        // todo
    }

    @Override
    public BigDecimal getPercentageOfBuyOrderTakenForExchangeFee(String marketId) throws TradingApiException, ExchangeNetworkException {
        return buyFeePercentage;
    }

    @Override
    public BigDecimal getPercentageOfSellOrderTakenForExchangeFee(String marketId) throws TradingApiException, ExchangeNetworkException {
        return sellFeePercentage;
    }
}
