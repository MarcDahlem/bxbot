package com.gazbert.bxbot.strategies.helper;

import com.gazbert.bxbot.trading.api.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ta4j.core.BarSeries;

import javax.xml.datatype.Duration;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class IntelligentPriceTracker {

    private static final Logger LOG = LogManager.getLogger();

    private final TradingApi tradingApi;
    private final Market market;
    private final BarSeries series;

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

    public BigDecimal getBid() {
        return (BigDecimal) series.getLastBar().getLowPrice().getDelegate();
    }

    public BigDecimal getLast() {
        return (BigDecimal) series.getLastBar().getClosePrice().getDelegate();
    }
}
