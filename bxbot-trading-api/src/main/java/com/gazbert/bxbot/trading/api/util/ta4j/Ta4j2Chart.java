package com.gazbert.bxbot.trading.api.util.ta4j;

import static org.ta4j.core.num.NaN.NaN;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import org.knowm.xchart.*;
import org.knowm.xchart.internal.Utils;
import org.knowm.xchart.internal.chartpart.Chart;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.markers.SeriesMarkers;
import org.ta4j.core.*;
import org.ta4j.core.num.Num;

import java.awt.*;
import java.util.List;
import java.util.*;

public class Ta4j2Chart {

    public static final Color CLOSE_PRICE_COLOR = new Color(183, 28, 28);
    public static final Color BID_PRICE_COLOR = new Color(255, 111, 0);
    public static final Color ASK_PRICE_COLOR = new Color(130, 119, 23);

    public static final Color BREAK_EVEN_COLOR = new Color(0, 200, 83);

    public static final Color BUY_TRIGGER_COLOR = new Color(0, 229, 255);
    public static final Color BUY_LONG_LOOKBACK_COLOR = new Color(106, 27, 154);
    public static final Color BUY_SHORT_LOOKBACK_COLOR = new Color(171, 71, 188);

    public static final Color SELL_CURRENT_LIMIT_COLOR = new Color(0, 0, 255);
    public static final Color SELL_LIMIT_1_COLOR = new Color(255, 0, 255);
    public static final Color SELL_LIMIT_2_COLOR = new Color(251, 192, 45);
    public static final Color SELL_LIMIT_3_COLOR = new Color(0, 131, 143);

    public static final Color AREA_COLOR_LINE_1 = new Color(103, 58, 183, 64);
    public static final Color AREA_COLOR_1 = new Color(124, 77, 255, 64);
    public static final Color AREA_COLOR_LINE_2 = new Color(0, 150, 136, 64);
    public static final Color AREA_COLOR_2 = new Color(100, 255, 218, 128);

    private static final Map<String, LiveChartConfig> liveCharts = new HashMap<>();

    public static void printSeries(
            BarSeries series, Strategy strategy, Collection<ChartIndicatorConfig> indicators) {
        OHLCChart chart =
                new OHLCChartBuilder()
                        .title(strategy.getName())
                        .xAxisTitle("Date")
                        .yAxisTitle("Price")
                        .build();
        chart.getStyler().setToolTipsEnabled(true);

        addOhlcToChart(chart, series, false, null);

        for (ChartIndicatorConfig indicatorConfig : indicators) {
            addIndicatorToChart(indicatorConfig, chart, series, false, null);
        }

        addPositionMarkerToChart(chart, strategy, series);
        new SwingWrapper<>(chart).displayChart();
    }

    private static void addPositionMarkerToChart(OHLCChart chart, Strategy strategy, BarSeries series) {
        BarSeriesManager seriesManager = new BarSeriesManager(series);
        List<Position> positions = seriesManager.run(strategy).getPositions();
        for (Position position : positions) {
            // Buy signal
            Date entryDate =
                    Date.from(series.getBar(position.getEntry().getIndex()).getEndTime().toInstant());
            double entryDateAsDouble = Utils.getDoubleArrayFromDateList(List.of(entryDate))[0];
            AnnotationLine buyAnnotation = new AnnotationLine(entryDateAsDouble, true, false);
            buyAnnotation.setColor(Color.GREEN);
            chart.addAnnotation(buyAnnotation);

            Date exitDate =
                    Date.from(series.getBar(position.getExit().getIndex()).getEndTime().toInstant());
            double exitDateAsDouble = Utils.getDoubleArrayFromDateList(List.of(exitDate))[0];
            AnnotationLine sellAnotation = new AnnotationLine(exitDateAsDouble, true, false);
            buyAnnotation.setColor(Color.RED);
            chart.addAnnotation(sellAnotation);
        }
    }

    private static void addIndicatorToChart(
            ChartIndicatorConfig indicatorConfig,
            OHLCChart chart,
            BarSeries series,
            boolean update,
            Integer limit) {
        List<Date> dates = new LinkedList<>();
        List<Number> values = new LinkedList<>();
        int startIndex = series.getBeginIndex();
        if (limit != null) {
            startIndex = Math.max(startIndex, series.getEndIndex() - limit);
        }

        Long marketCaptureTicksInMillis = null;

        appendLeadingBarsForBarsPrintedInFuture(indicatorConfig, series, dates, values, startIndex);

        for (int i = startIndex; i <= series.getEndIndex(); i++) {
            int indexWithPrintDelay = i - indicatorConfig.printDelay;
            if (indexWithPrintDelay >= startIndex) {
                if (indexWithPrintDelay <= series.getEndIndex()) {
                    Bar delayedBar = series.getBar(indexWithPrintDelay);
                    dates.add(Date.from(delayedBar.getEndTime().toInstant()));
                    values.add(indicatorConfig.indicator.getValue(i).getDelegate());
                } else {
                    if (marketCaptureTicksInMillis == null) {
                        marketCaptureTicksInMillis = computeApproximateMarketTicks(series);
                    }
                    dates.add(Date.from(series.getBar(i).getEndTime().plus((indicatorConfig.printDelay * -1) * marketCaptureTicksInMillis, ChronoUnit.MILLIS).toInstant()));
                    values.add(indicatorConfig.indicator.getValue(i).getDelegate());
                }
            } else {
                dates.add(Date.from(series.getBar(startIndex).getEndTime().toInstant()));
                values.add(NaN.getDelegate());
            }
        }
        if (update) {
            chart.updateOHLCSeries(indicatorConfig.name, dates, values);
        } else {
            OHLCSeries chartSeries = chart.addSeries(indicatorConfig.name, dates, values);
            chartSeries.setMarker(SeriesMarkers.NONE);

            if (indicatorConfig.color != null) {
                chartSeries.setLineColor(indicatorConfig.color);
            }

            if (indicatorConfig.yAxisGroup != null) {
                chartSeries.setYAxisGroup(indicatorConfig.yAxisGroup.yAxisGroupIndex);
                chart.setYAxisGroupTitle(
                        indicatorConfig.yAxisGroup.yAxisGroupIndex, indicatorConfig.yAxisGroup.yAxisGroupLabel);
                chartSeries.setFillColor(indicatorConfig.yAxisGroup.areaFillColor);
            }
        }
    }

    private static void addOhlcToChart(
            OHLCChart chart,
            BarSeries series,
            boolean update,
            Integer limit) {
        List<Date> dates = new LinkedList<>();
        List<Number> opens = new LinkedList<>();
        List<Number> highs = new LinkedList<>();
        List<Number> lows = new LinkedList<>();
        List<Number> closes = new LinkedList<>();
        List<Number> volumes = new LinkedList<>();
        int startIndex = series.getBeginIndex();
        if (limit != null) {
            startIndex = Math.max(startIndex, series.getEndIndex() - limit);
        }

        for (int i = startIndex; i <= series.getEndIndex(); i++) {
                Bar bar = series.getBar(i);
                dates.add(Date.from(bar.getEndTime().toInstant()));
                opens.add(bar.getOpenPrice().getDelegate());
                highs.add(bar.getHighPrice().getDelegate());
                lows.add(bar.getLowPrice().getDelegate());
                closes.add(bar.getClosePrice().getDelegate());
                volumes.add(bar.getVolume().getDelegate());
        }
        if (update) {
            chart.updateOHLCSeries("ohlc", dates, opens, highs, lows, closes);
        } else {
            OHLCSeries chartSeries = chart.addSeries("ohlc", dates, opens, highs, lows, closes, volumes);
            // fix https://github.com/knowm/XChart/issues/567 not yet released
            chartSeries.setDownColor(new Color(242, 39, 42));
            chartSeries.setUpColor(new Color(19, 179, 70));
        }
    }

    private static void appendLeadingBarsForBarsPrintedInFuture(ChartIndicatorConfig indicatorConfig, BarSeries series, List<Date> dates, List<Number> values, int startIndex) {
        for (int i = startIndex; i < startIndex - indicatorConfig.printDelay; i++) {
            int indexInPast = i + indicatorConfig.printDelay;
            if (indexInPast >= series.getBeginIndex()) {
                if (i <= series.getEndIndex()) {
                    Bar currentBar = series.getBar(i);
                    dates.add(Date.from(currentBar.getEndTime().toInstant()));
                } else {
                    Long marketCaptureTicksInMillis = computeApproximateMarketTicks(series);
                    dates.add(Date.from(series.getBar(indexInPast).getEndTime().plus((indicatorConfig.printDelay * -1) * marketCaptureTicksInMillis, ChronoUnit.MILLIS).toInstant()));
                }
                values.add(indicatorConfig.indicator.getValue(indexInPast).getDelegate());
            }
        }
    }

    private static Long computeApproximateMarketTicks(BarSeries series) {
        if (series.getBarCount() < 1) {
            return 3L;
        }

        ZonedDateTime firstBarTime = series.getFirstBar().getEndTime();
        ZonedDateTime lastBarTime = series.getLastBar().getEndTime();

        long ellapsedTime = ChronoUnit.MILLIS.between(firstBarTime, lastBarTime);
        return ellapsedTime / series.getBarCount();
    }

    public static String createLiveChart(
            BarSeries series, Collection<ChartIndicatorConfig> indicatorConfigs, Integer maxAmountBars) {
        OHLCChart chart =
                new OHLCChartBuilder()
                        .title(series.getName())
                        .xAxisTitle("Date")
                        .yAxisTitle("Price")
                        .height(900 / 3)
                        .width(1680 / 3)
                        .build();
        chart.getStyler().setLegendPosition(Styler.LegendPosition.InsideNW);

        // chart.getStyler().setCursorEnabled(true); //disable cursor and tooltip, as it has memory leaks in live
        // charts. Check https://github.com/knowm/XChart/issues/593
        //chart.getStyler().setToolTipsEnabled(true);

        addOhlcToChart(chart, series, false, maxAmountBars);

        for (ChartIndicatorConfig indicator : indicatorConfigs) {
            addIndicatorToChart(indicator, chart, series, false, maxAmountBars);
        }



        SwingWrapper<OHLCChart> sw = new SwingWrapper<>(chart);
        sw.isCentered(false);

        String liveChartID = UUID.randomUUID().toString();
        liveCharts.put(
                liveChartID, new LiveChartConfig(sw, chart, series, indicatorConfigs, maxAmountBars));
        sw.displayChart();

        return liveChartID;
    }

    public static void updateLiveChart(String liveChartID) {
        if (!liveCharts.containsKey(liveChartID)) {
            throw new IllegalArgumentException("No live chart with id '" + liveChartID + "' found");
        }
        LiveChartConfig liveChartConfig = liveCharts.get(liveChartID);
        if (liveChartConfig.waitForRun) {
            return;
        }
        liveChartConfig.waitForRun = true;

        javax.swing.SwingUtilities.invokeLater(
                () -> {
                    addOhlcToChart(liveChartConfig.chart, liveChartConfig.series, true, liveChartConfig.maxAmountBars);
                    for (ChartIndicatorConfig indicatorConfig : liveChartConfig.indicatorConfigs) {
                        addIndicatorToChart(
                                indicatorConfig,
                                liveChartConfig.chart,
                                liveChartConfig.series,
                                true,
                                liveChartConfig.maxAmountBars);
                    }
                    liveChartConfig.sw.repaintChart();
                    liveChartConfig.waitForRun = false;
                });
    }

    private static class LiveChartConfig {
        final SwingWrapper<OHLCChart> sw;
        final OHLCChart chart;
        final BarSeries series;
        final Collection<ChartIndicatorConfig> indicatorConfigs;
        final Integer maxAmountBars;
        volatile boolean waitForRun;

        LiveChartConfig(
                SwingWrapper<OHLCChart> sw,
                OHLCChart chart,
                BarSeries series,
                Collection<ChartIndicatorConfig> indicatorConfigs,
                Integer maxAmountBars) {
            this.sw = sw;
            this.chart = chart;
            this.series = series;
            this.indicatorConfigs = indicatorConfigs;
            this.maxAmountBars = maxAmountBars;
        }
    }

    public static class ChartIndicatorConfig {
        final Indicator<Num> indicator;
        final String name;
        final Color color;
        final YAxisGroupConfig yAxisGroup;
        final int printDelay;

        public ChartIndicatorConfig(Indicator<Num> indicator, String name, Color color) {
            this(indicator, name, color, 0);
        }

        public ChartIndicatorConfig(Indicator<Num> indicator, String name, Color color, int printDelay) {
            this(indicator, name, color, printDelay, null);
        }

        public ChartIndicatorConfig(
                Indicator<Num> indicator, String name, Color color, YAxisGroupConfig yAxisGroup) {
            this(indicator, name, color, 0, yAxisGroup);
        }

        public ChartIndicatorConfig(
                Indicator<Num> indicator, String name, Color color, int printDelay, YAxisGroupConfig yAxisGroup) {
            this.indicator = indicator;
            this.name = name;
            this.color = color;
            this.printDelay = printDelay;
            this.yAxisGroup = yAxisGroup;
        }
    }

    public static class YAxisGroupConfig {
        final String yAxisGroupLabel;
        final Integer yAxisGroupIndex;
        final Color areaFillColor;

        public YAxisGroupConfig(String yAxisGroupLabel, Integer yAxisGroupIndex, Color areaFillColor) {

            this.yAxisGroupLabel = yAxisGroupLabel;
            this.yAxisGroupIndex = yAxisGroupIndex;
            this.areaFillColor = areaFillColor;
        }
    }
}
