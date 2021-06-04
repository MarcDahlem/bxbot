package com.gazbert.bxbot.trading.api;

import static org.junit.Assert.assertEquals;
import static org.ta4j.core.num.NaN.NaN;

import com.gazbert.bxbot.trading.api.util.ta4j.ReversalPointsIndicator;
import java.time.ZonedDateTime;
import java.util.LinkedList;
import org.junit.Before;
import org.junit.Test;
import org.ta4j.core.Bar;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;


public class ReversalPointsIndicatorTest {

  private int amountConfirmationsNeeded;
  private BaseBarSeries series;
  private ZonedDateTime startTime;

  @Before
  public void setupTests() {
    amountConfirmationsNeeded = 2;
    series = new BaseBarSeries();
    startTime = ZonedDateTime.now();
  }

  @Test
  public void testSimpleConfirmedHighs() {
    series.addBar(startTime, 2, 3, 1, 2);


    ReversalPointsIndicator indicator = new ReversalPointsIndicator(series, ReversalPointsIndicator.ReversalType.HIGHS);
    assertEquals(NaN, indicator.getValue(0));

    series.addBar(startTime.plusDays(1), 2, 2.9, 0.9, 2);

    assertEquals(NaN, indicator.getValue(0));
    assertEquals(NaN, indicator.getValue(1));

    series.addBar(startTime.plusDays(2), 2, 2.8, 0.8, 2);
    assertEquals(series.numOf(3), indicator.getValue(0));
    assertEquals(series.numOf(3), indicator.getValue(1));
    assertEquals(series.numOf(3), indicator.getValue(2));
  }

  @Test
  public void testSimpleConfirmedLows() {
    series.addBar(startTime, 2, 3, 1, 2);


    ReversalPointsIndicator indicator = new ReversalPointsIndicator(series, ReversalPointsIndicator.ReversalType.LOWS);
    assertEquals(NaN, indicator.getValue(0));

    series.addBar(startTime.plusDays(1), 2, 3.1, 1.1, 2);

    assertEquals(NaN, indicator.getValue(0));
    assertEquals(NaN, indicator.getValue(1));

    series.addBar(startTime.plusDays(2), 2, 2.9, 1.2, 2);
    assertEquals(NaN, indicator.getValue(0));
    assertEquals(NaN, indicator.getValue(1));
    assertEquals(NaN, indicator.getValue(2));

    series.addBar(startTime.plusDays(3), 2, 3.2, 1.2, 2);
    assertEquals(series.numOf(1), indicator.getValue(0));
    assertEquals(series.numOf(1), indicator.getValue(1));
    assertEquals(series.numOf(1), indicator.getValue(2));
    assertEquals(series.numOf(1), indicator.getValue(3));
  }
}
