package com.gazbert.bxbot.trading.api.util.ta4j;

import org.knowm.xchart.*;
import org.knowm.xchart.internal.Utils;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.markers.SeriesMarkers;
import org.ta4j.core.*;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;
import org.ta4j.core.num.Num;

import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class BuyAndSellSignalsToChart {

    public static void printSeries(BarSeries series, Strategy strategy, Map<? extends Indicator<Num>, String> indicators) {
        System.setProperty("java.awt.headless", "false");
        XYChart chart = new XYChartBuilder().title(strategy.getName()).xAxisTitle("Date").yAxisTitle("Price").build();
        chart.getStyler().setZoomEnabled(true);
        chart.getStyler().setCursorEnabled(true);

        for (Map.Entry<? extends Indicator<Num>, String> indicator : indicators.entrySet()) {
            addIndicatorToChart(indicator, chart, series);
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

    private static void addIndicatorToChart(Map.Entry<? extends Indicator<Num>, String> indicator, XYChart chart, BarSeries series) {
        List<Date> dates= new LinkedList<>();
        List<Number> values = new LinkedList<>();
        for (int i = series.getBeginIndex(); i <= series.getEndIndex(); i++) {
            Bar bar = series.getBar(i);
            dates.add(Date.from(bar.getEndTime().toInstant()));
            values.add(indicator.getKey().getValue(i).getDelegate());
        }
        XYSeries chartSeries = chart.addSeries(indicator.getValue(), dates, values);
        chartSeries.setSmooth(false);
        chartSeries.setMarker(SeriesMarkers.NONE);
        if (indicator.getKey() instanceof StochasticOscillatorKIndicator) {
            chartSeries.setYAxisGroup(1);
            chart.setYAxisGroupTitle(1, "K osci");
            chartSeries.setXYSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Area);
            chartSeries.setLineColor(new Color(0,150,136,64));
            chartSeries.setFillColor(new Color(100,255,218, 128));
        } else {
            if (indicator.getKey() instanceof MACDIndicator) {
                chartSeries.setYAxisGroup(2);
                chart.setYAxisGroupTitle(2, "MACD");
                chartSeries.setXYSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Area);
                chartSeries.setLineColor(new Color(103,58,183,64));
                chartSeries.setFillColor(new Color(124,77,255, 64));
            }
        }




    }
}
