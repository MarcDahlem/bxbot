package com.gazbert.bxbot.trading.api.util.ta4j;

import com.gazbert.bxbot.trading.api.TradingApiException;
import org.knowm.xchart.*;
import org.knowm.xchart.internal.Utils;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.markers.SeriesMarkers;
import org.ta4j.core.*;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;
import org.ta4j.core.num.Num;

import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

public class Ta4j2Chart {

    private static final Map<String, LiveChartConfig> liveCharts = new HashMap<>();

    public static void printSeries(BarSeries series, Strategy strategy, Collection<ChartIndicatorConfig> indicators) {
        XYChart chart = new XYChartBuilder().title(strategy.getName()).xAxisTitle("Date").yAxisTitle("Price").build();
        chart.getStyler().setZoomEnabled(true);
        chart.getStyler().setCursorEnabled(true);

        for (ChartIndicatorConfig indicatorConfig : indicators) {
            addIndicatorToChart(indicatorConfig, chart, series, false, null);
        }

        addPositionMarkerToChart(chart, strategy, series);
        new SwingWrapper(chart).displayChart();
    }

    private static void addPositionMarkerToChart(XYChart chart, Strategy strategy, BarSeries series) {
        BarSeriesManager seriesManager = new BarSeriesManager(series);
        List<Position> positions = seriesManager.run(strategy).getPositions();
        for (Position position : positions) {
            // Buy signal
            Date entryDate = Date.from(series.getBar(position.getEntry().getIndex()).getEndTime().toInstant());
            double entryDateAsDouble = Utils.getDoubleArrayFromDateList(List.of(entryDate))[0];
            AnnotationLine buyAnnotation = new AnnotationLine(entryDateAsDouble, true, false);
            buyAnnotation.setColor(Color.GREEN);
            chart.addAnnotation(buyAnnotation);

            Date exitDate = Date.from(series.getBar(position.getExit().getIndex()).getEndTime().toInstant());
            double exitDateAsDouble = Utils.getDoubleArrayFromDateList(List.of(exitDate))[0];
            AnnotationLine sellAnotation = new AnnotationLine(exitDateAsDouble, true, false);
            buyAnnotation.setColor(Color.RED);
            chart.addAnnotation(sellAnotation);
        }
    }

    private static void addIndicatorToChart(ChartIndicatorConfig indicatorConfig, XYChart chart, BarSeries series, boolean update, Integer limit) {
        List<Date> dates = new LinkedList<>();
        List<Number> values = new LinkedList<>();
        int startIndex = series.getBeginIndex();
        if (limit != null) {
            startIndex = Math.max(startIndex, series.getEndIndex() - limit);
        }

        for (int i = startIndex; i <= series.getEndIndex(); i++) {
            Bar bar = series.getBar(i);
            dates.add(Date.from(bar.getEndTime().toInstant()));
            values.add(indicatorConfig.indicator.getValue(i).getDelegate());
        }
        if (update) {
            chart.updateXYSeries(indicatorConfig.name, dates, values, null);
        } else {
            XYSeries chartSeries = chart.addSeries(indicatorConfig.name, dates, values);
            chartSeries.setSmooth(false);
            chartSeries.setMarker(SeriesMarkers.NONE);

            if (indicatorConfig.color != null) {
                chartSeries.setLineColor(indicatorConfig.color);
            }

            if (indicatorConfig.yAxisGroup != null) {
                chartSeries.setYAxisGroup(indicatorConfig.yAxisGroup.yAxisGroupIndex);
                chart.setYAxisGroupTitle(indicatorConfig.yAxisGroup.yAxisGroupIndex, indicatorConfig.yAxisGroup.yAxisGroupLabel);
                chartSeries.setXYSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Area);
                chartSeries.setFillColor(indicatorConfig.yAxisGroup.areaFillColor);
            }
        }
    }

    public static String createLiveChart(BarSeries series, Collection<ChartIndicatorConfig> indicatorConfigs, Integer maxAmountBars) {
        XYChart chart = new XYChartBuilder().title(series.getName()).xAxisTitle("Date").yAxisTitle("Price").build();
        chart.getStyler().setZoomEnabled(true);
        chart.getStyler().setCursorEnabled(true);

        for (ChartIndicatorConfig indicator : indicatorConfigs) {
            addIndicatorToChart(indicator, chart, series, false, maxAmountBars);
        }

        SwingWrapper sw = new SwingWrapper(chart);

        String liveChartID = UUID.randomUUID().toString();
        liveCharts.put(liveChartID, new LiveChartConfig(sw, chart, series, indicatorConfigs, maxAmountBars));
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

        javax.swing.SwingUtilities.invokeLater(() -> {
            for (ChartIndicatorConfig indicatorConfig : liveChartConfig.indicatorConfigs) {
                addIndicatorToChart(indicatorConfig, liveChartConfig.chart, liveChartConfig.series, true, liveChartConfig.maxAmountBars);
            }
            liveChartConfig.sw.repaintChart();
            liveChartConfig.waitForRun = false;
        });
    }

    private static class LiveChartConfig {
        final SwingWrapper sw;
        final XYChart chart;
        final BarSeries series;
        final Collection<ChartIndicatorConfig> indicatorConfigs;
        final Integer maxAmountBars;
        volatile boolean waitForRun;

        LiveChartConfig(SwingWrapper sw, XYChart chart, BarSeries series, Collection<ChartIndicatorConfig> indicatorConfigs, Integer maxAmountBars) {
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

        public ChartIndicatorConfig(Indicator<Num> indicator, String name) {
            this(indicator, name, null);
        }

        public ChartIndicatorConfig(Indicator<Num> indicator, String name, Color color) {
            this(indicator, name, color, null);

        }

        public ChartIndicatorConfig(Indicator<Num> indicator, String name, Color color, YAxisGroupConfig yAxisGroup) {
            this.indicator = indicator;
            this.name = name;
            this.color = color;
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
