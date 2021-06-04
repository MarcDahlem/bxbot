package com.gazbert.bxbot.trading.api;

import static org.junit.Assert.assertEquals;
import static org.ta4j.core.num.NaN.NaN;

import com.gazbert.bxbot.trading.api.util.ta4j.ReversalPointsIndicator;
import java.time.ZonedDateTime;
import org.junit.Before;
import org.junit.Test;
import org.ta4j.core.BaseBarSeries;


public class ReversalPointsIndicatorTest {

    private BaseBarSeries series;
    private ZonedDateTime startTime;

    @Before
    public void setupTests() {
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

    @Test
    public void canHandleUpsAndDowns() {
        series.addBar(startTime, 2, 2, 2, 2);
        series.addBar(startTime.plusDays(1), 3, 3, 3, 3);
        series.addBar(startTime.plusDays(2), 2, 2, 2, 2);
        series.addBar(startTime.plusDays(3), 1.5, 1.5, 1.5, 1.5);
        series.addBar(startTime.plusDays(4), 1, 1, 1, 1);
        series.addBar(startTime.plusDays(5), 1.5, 1.5, 1.5, 1.5);
        series.addBar(startTime.plusDays(6), 1.75, 1.75, 1.75, 1.75);


        ReversalPointsIndicator lowsIndicator = new ReversalPointsIndicator(series, ReversalPointsIndicator.ReversalType.LOWS);
        ReversalPointsIndicator highsIndicator = new ReversalPointsIndicator(series, ReversalPointsIndicator.ReversalType.HIGHS);
        assertEquals(NaN, lowsIndicator.getValue(0));
        assertEquals(NaN, lowsIndicator.getValue(1));
        assertEquals(NaN, lowsIndicator.getValue(2));
        assertEquals(NaN, lowsIndicator.getValue(3));
        assertEquals(series.numOf(1), lowsIndicator.getValue(4));
        assertEquals(series.numOf(1), lowsIndicator.getValue(5));
        assertEquals(series.numOf(1), lowsIndicator.getValue(6));

        assertEquals(NaN, highsIndicator.getValue(0));
        assertEquals(series.numOf(3), highsIndicator.getValue(1));
        assertEquals(series.numOf(3), highsIndicator.getValue(2));
        assertEquals(series.numOf(3), highsIndicator.getValue(3));
        assertEquals(series.numOf(3), highsIndicator.getValue(4));
        assertEquals(series.numOf(3), highsIndicator.getValue(5));
        assertEquals(series.numOf(3), highsIndicator.getValue(6));
    }

    @Test
    public void canHandleMultipleHighsAndLows() {
        series.addBar(startTime, 4, 4, 4, 4);
        series.addBar(startTime.plusDays(1), 3, 3, 3, 3);
        series.addBar(startTime.plusDays(2), 2, 2, 2, 2);
        series.addBar(startTime.plusDays(3), 1, 1, 1, 1);
        series.addBar(startTime.plusDays(4), 1.5, 1.5, 1.5, 1.5);
        series.addBar(startTime.plusDays(5), 1.75, 1.75, 1.75, 1.75);
        series.addBar(startTime.plusDays(6), 1.45, 1.45, 1.45, 1.45);
        series.addBar(startTime.plusDays(7), 1.99, 1.99, 1.99, 1.99);
        series.addBar(startTime.plusDays(8), 1.32, 1.32, 1.32, 1.32);
        series.addBar(startTime.plusDays(9), 1.5, 1.98, 1.45, 1.48);
        series.addBar(startTime.plusDays(10), 1.1, 1.1, 1.1, 1.1);


        ReversalPointsIndicator lowsIndicator = new ReversalPointsIndicator(series, ReversalPointsIndicator.ReversalType.LOWS);
        ReversalPointsIndicator highsIndicator = new ReversalPointsIndicator(series, ReversalPointsIndicator.ReversalType.HIGHS);
        assertEquals(NaN, lowsIndicator.getValue(0));
        assertEquals(NaN, lowsIndicator.getValue(1));
        assertEquals(NaN, lowsIndicator.getValue(2));
        assertEquals(series.numOf(1), lowsIndicator.getValue(3));
        assertEquals(series.numOf(1), lowsIndicator.getValue(4));
        assertEquals(series.numOf(1), lowsIndicator.getValue(5));
        assertEquals(series.numOf(1), lowsIndicator.getValue(6));
        assertEquals(series.numOf(1), lowsIndicator.getValue(7));
        assertEquals(series.numOf(1), lowsIndicator.getValue(8));
        assertEquals(series.numOf(1), lowsIndicator.getValue(9));
        assertEquals(series.numOf(1), lowsIndicator.getValue(10));

        assertEquals(series.numOf(4), highsIndicator.getValue(0));
        assertEquals(series.numOf(4), highsIndicator.getValue(1));
        assertEquals(series.numOf(4), highsIndicator.getValue(2));
        assertEquals(series.numOf(4), highsIndicator.getValue(3));
        assertEquals(series.numOf(4), highsIndicator.getValue(4));
        assertEquals(series.numOf(4), highsIndicator.getValue(5));
        assertEquals(series.numOf(4), highsIndicator.getValue(6));
        assertEquals(series.numOf(1.99), highsIndicator.getValue(7));
        assertEquals(series.numOf(1.99), highsIndicator.getValue(8));
        assertEquals(series.numOf(1.99), highsIndicator.getValue(9));
        assertEquals(series.numOf(1.99), highsIndicator.getValue(10));
    }

    @Test
    public void lastHighConfirmationCannotBeConfirmedLow() {
        series.addBar(startTime, 4, 4, 4, 4);
        series.addBar(startTime.plusDays(1), 3, 3, 3, 3);
        series.addBar(startTime.plusDays(2), 2, 2, 2, 2);
        series.addBar(startTime.plusDays(3), 2.5, 2.5, 2.5, 2.5, 2.5);
        series.addBar(startTime.plusDays(4), 2.75, 2.75, 2.75, 2.75);

        ReversalPointsIndicator lowsIndicator = new ReversalPointsIndicator(series, ReversalPointsIndicator.ReversalType.LOWS);
        ReversalPointsIndicator highsIndicator = new ReversalPointsIndicator(series, ReversalPointsIndicator.ReversalType.HIGHS);
        assertEquals(NaN, lowsIndicator.getValue(0));
        assertEquals(NaN, lowsIndicator.getValue(1));
        assertEquals(series.numOf(2), lowsIndicator.getValue(2));
        assertEquals(series.numOf(2), lowsIndicator.getValue(3));
        assertEquals(series.numOf(2), lowsIndicator.getValue(4));

        assertEquals(NaN, highsIndicator.getValue(0));
        assertEquals(NaN, highsIndicator.getValue(1));
        assertEquals(NaN, highsIndicator.getValue(2));
        assertEquals(NaN, highsIndicator.getValue(3));
        assertEquals(NaN, highsIndicator.getValue(4));
    }

    @Test
    public void lastLowConfirmationCannotBeConfirmedHigh() {
        series.addBar(startTime, 1, 1, 1, 1);
        series.addBar(startTime.plusDays(1), 2, 2, 2, 2);
        series.addBar(startTime.plusDays(2), 3, 3, 3, 3);
        series.addBar(startTime.plusDays(3), 2.5, 2.5, 2.5, 2.5, 2.5);
        series.addBar(startTime.plusDays(4), 2.25, 2.25, 2.25, 2.25);

        ReversalPointsIndicator lowsIndicator = new ReversalPointsIndicator(series, ReversalPointsIndicator.ReversalType.LOWS);
        ReversalPointsIndicator highsIndicator = new ReversalPointsIndicator(series, ReversalPointsIndicator.ReversalType.HIGHS);

        assertEquals(NaN, lowsIndicator.getValue(0));
        assertEquals(NaN, lowsIndicator.getValue(1));
        assertEquals(NaN, lowsIndicator.getValue(2));
        assertEquals(NaN, lowsIndicator.getValue(3));
        assertEquals(NaN, lowsIndicator.getValue(4));

        assertEquals(NaN, highsIndicator.getValue(0));
        assertEquals(NaN, highsIndicator.getValue(1));
        assertEquals(series.numOf(3), highsIndicator.getValue(2));
        assertEquals(series.numOf(3), highsIndicator.getValue(3));
        assertEquals(series.numOf(3), highsIndicator.getValue(4));
    }

    @Test
    public void twoHigherBarsConfirmALow() {
        // https://www.marketsmadeclear.com/Resources/Currency-Strength-Matrix/How-to-read-a-chart.aspx
        series.addBar(startTime, 5, 7, 1, 3);
        series.addBar(startTime.plusDays(1), 4, 10, 2, 9);
        series.addBar(startTime.plusDays(2), 8, 12, 6, 11);

        ReversalPointsIndicator lowsIndicator = new ReversalPointsIndicator(series, ReversalPointsIndicator.ReversalType.LOWS);
        ReversalPointsIndicator highsIndicator = new ReversalPointsIndicator(series, ReversalPointsIndicator.ReversalType.HIGHS);

        assertEquals(series.numOf(1), lowsIndicator.getValue(0));
        assertEquals(series.numOf(1), lowsIndicator.getValue(1));
        assertEquals(series.numOf(1), lowsIndicator.getValue(2));

        assertEquals(NaN, highsIndicator.getValue(0));
        assertEquals(NaN, highsIndicator.getValue(1));
        assertEquals(NaN, highsIndicator.getValue(2));
    }

    @Test
    public void barsDoNotNeedToBeConsecutive() {
        // https://www.marketsmadeclear.com/Resources/Currency-Strength-Matrix/How-to-read-a-chart.aspx
        series.addBar(startTime, 2, 13, 1, 6);
        series.addBar(startTime.plusDays(1), 7, 18, 5, 11);
        series.addBar(startTime.plusDays(2), 10, 17, 4, 8);
        series.addBar(startTime.plusDays(3), 9, 16, 3, 14);
        series.addBar(startTime.plusDays(4), 15, 20, 12, 19);

        ReversalPointsIndicator lowsIndicator = new ReversalPointsIndicator(series, ReversalPointsIndicator.ReversalType.LOWS);
        ReversalPointsIndicator highsIndicator = new ReversalPointsIndicator(series, ReversalPointsIndicator.ReversalType.HIGHS);

        assertEquals(series.numOf(1), lowsIndicator.getValue(0));
        assertEquals(series.numOf(1), lowsIndicator.getValue(1));
        assertEquals(series.numOf(1), lowsIndicator.getValue(2));
        assertEquals(series.numOf(1), lowsIndicator.getValue(3));
        assertEquals(series.numOf(1), lowsIndicator.getValue(4));

        assertEquals(NaN, highsIndicator.getValue(0));
        assertEquals(NaN, highsIndicator.getValue(1));
        assertEquals(NaN, highsIndicator.getValue(2));
        assertEquals(NaN, highsIndicator.getValue(3));
        assertEquals(NaN, highsIndicator.getValue(4));
    }

    @Test
    public void twoLowerBarsConfirmAHigh() {
        // https://www.marketsmadeclear.com/Resources/Currency-Strength-Matrix/How-to-read-a-chart.aspx
        series.addBar(startTime, 13, 16, 5, 8);
        series.addBar(startTime.plusDays(1), 9, 15, 6, 11);
        series.addBar(startTime.plusDays(2), 10, 14, 2, 4);
        series.addBar(startTime.plusDays(3), 3, 12, 1, 7);

        ReversalPointsIndicator lowsIndicator = new ReversalPointsIndicator(series, ReversalPointsIndicator.ReversalType.LOWS);
        ReversalPointsIndicator highsIndicator = new ReversalPointsIndicator(series, ReversalPointsIndicator.ReversalType.HIGHS);

        assertEquals(NaN, lowsIndicator.getValue(0));
        assertEquals(NaN, lowsIndicator.getValue(1));
        assertEquals(NaN, lowsIndicator.getValue(2));
        assertEquals(NaN, lowsIndicator.getValue(3));

        assertEquals(series.numOf(16), highsIndicator.getValue(0));
        assertEquals(series.numOf(16), highsIndicator.getValue(1));
        assertEquals(series.numOf(16), highsIndicator.getValue(2));
        assertEquals(series.numOf(16), highsIndicator.getValue(3));
    }

    @Test
    public void adaptToNewPriceAction() {
        // https://www.marketsmadeclear.com/Resources/Currency-Strength-Matrix/How-to-read-a-chart.aspx
        series.addBar(startTime,9,26 ,6, 18);
        series.addBar(startTime.plusDays(1),15, 30,3,8);
        series.addBar(startTime.plusDays(2),7,22 ,2,12);
        series.addBar(startTime.plusDays(3),14,27 ,5 , 23);

        ReversalPointsIndicator lowsIndicator = new ReversalPointsIndicator(series, ReversalPointsIndicator.ReversalType.LOWS);
        ReversalPointsIndicator highsIndicator = new ReversalPointsIndicator(series, ReversalPointsIndicator.ReversalType.HIGHS);


        assertEquals(NaN, lowsIndicator.getValue(0));
        assertEquals(NaN, lowsIndicator.getValue(1));
        assertEquals(NaN, lowsIndicator.getValue(2));
        assertEquals(NaN, lowsIndicator.getValue(3));

        assertEquals(NaN, highsIndicator.getValue(0));
        assertEquals(NaN, highsIndicator.getValue(1));
        assertEquals(NaN, highsIndicator.getValue(2));
        assertEquals(NaN, highsIndicator.getValue(3));

        series.addBar(startTime.plusDays(4),24 , 29,10,13);

        assertEquals(NaN, lowsIndicator.getValue(0));
        assertEquals(NaN, lowsIndicator.getValue(1));
        assertEquals(series.numOf(2), lowsIndicator.getValue(2));
        assertEquals(series.numOf(2), lowsIndicator.getValue(3));
        assertEquals(series.numOf(2), lowsIndicator.getValue(4));

        assertEquals(NaN, highsIndicator.getValue(0));
        assertEquals(NaN, highsIndicator.getValue(1));
        assertEquals(NaN, highsIndicator.getValue(2));
        assertEquals(NaN, highsIndicator.getValue(3));
        assertEquals(NaN, highsIndicator.getValue(4));


        series.addBar(startTime.plusDays(5),11,21,1, 17);
        assertEquals(NaN, lowsIndicator.getValue(0));
        assertEquals(NaN, lowsIndicator.getValue(1));
        assertEquals(NaN, lowsIndicator.getValue(2));
        assertEquals(NaN, lowsIndicator.getValue(3));
        assertEquals(NaN, lowsIndicator.getValue(4));
        assertEquals(NaN, lowsIndicator.getValue(5));

        assertEquals(NaN, highsIndicator.getValue(0));
        assertEquals(series.numOf(30), highsIndicator.getValue(1));
        assertEquals(series.numOf(30), highsIndicator.getValue(2));
        assertEquals(series.numOf(30), highsIndicator.getValue(3));
        assertEquals(series.numOf(30), highsIndicator.getValue(4));
        assertEquals(series.numOf(30), highsIndicator.getValue(5));

        series.addBar(startTime.plusDays(6), 25,28 ,4, 19);
        series.addBar(startTime.plusDays(7), 20,36 , 16,33 );

        assertEquals(NaN, lowsIndicator.getValue(0));
        assertEquals(NaN, lowsIndicator.getValue(1));
        assertEquals(NaN, lowsIndicator.getValue(2));
        assertEquals(NaN, lowsIndicator.getValue(3));
        assertEquals(NaN, lowsIndicator.getValue(4));
        assertEquals(series.numOf(1), lowsIndicator.getValue(5));
        assertEquals(series.numOf(1), lowsIndicator.getValue(6));
        assertEquals(series.numOf(1), lowsIndicator.getValue(7));

        assertEquals(NaN, highsIndicator.getValue(0));
        assertEquals(NaN, highsIndicator.getValue(1));
        assertEquals(NaN, highsIndicator.getValue(2));
        assertEquals(NaN, highsIndicator.getValue(3));
        assertEquals(NaN, highsIndicator.getValue(4));
        assertEquals(NaN, highsIndicator.getValue(5));
        assertEquals(NaN, highsIndicator.getValue(6));
        assertEquals(NaN, highsIndicator.getValue(7));

        series.addBar(startTime.plusDays(8), 34, 35, 31, 32);

        assertEquals(NaN, lowsIndicator.getValue(0));
        assertEquals(NaN, lowsIndicator.getValue(1));
        assertEquals(NaN, lowsIndicator.getValue(2));
        assertEquals(NaN, lowsIndicator.getValue(3));
        assertEquals(NaN, lowsIndicator.getValue(4));
        assertEquals(series.numOf(1), lowsIndicator.getValue(5));
        assertEquals(series.numOf(1), lowsIndicator.getValue(6));
        assertEquals(series.numOf(1), lowsIndicator.getValue(7));
        assertEquals(series.numOf(1), lowsIndicator.getValue(8));

        assertEquals(NaN, highsIndicator.getValue(0));
        assertEquals(NaN, highsIndicator.getValue(1));
        assertEquals(NaN, highsIndicator.getValue(2));
        assertEquals(NaN, highsIndicator.getValue(3));
        assertEquals(NaN, highsIndicator.getValue(4));
        assertEquals(NaN, highsIndicator.getValue(5));
        assertEquals(NaN, highsIndicator.getValue(6));
        assertEquals(NaN, highsIndicator.getValue(7));
        assertEquals(NaN, highsIndicator.getValue(8));
    }

    @Test
    public void resetsHighsOnNonConfirmedLows() {
        //TODO
    }
}
