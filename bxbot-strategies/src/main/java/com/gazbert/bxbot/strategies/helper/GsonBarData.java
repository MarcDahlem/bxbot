package com.gazbert.bxbot.strategies.helper;

import org.ta4j.core.Bar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.num.Num;
import org.ta4j.core.num.PrecisionNum;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

public class GsonBarData {
    private long endTime;
    private Number openPrice;
    private Number highPrice;
    private Number lowPrice;
    private Number closePrice;

    public static GsonBarData from(Bar bar) {
        GsonBarData result = new GsonBarData();
        result.endTime = bar.getEndTime().toInstant().toEpochMilli();
        result.openPrice = bar.getOpenPrice().getDelegate();
        result.highPrice = bar.getHighPrice().getDelegate();
        result.lowPrice = bar.getLowPrice().getDelegate();
        result.closePrice = bar.getClosePrice().getDelegate();
        return result;
    }

    public void addTo(BaseBarSeries barSeries) {
        Instant i = Instant.ofEpochMilli(endTime);
        ZonedDateTime barTime = ZonedDateTime.ofInstant(i, ZoneId.systemDefault());
        barSeries.addBar(barTime, openPrice, highPrice, lowPrice, closePrice);
    }
}
