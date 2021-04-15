package com.gazbert.bxbot.strategies.helper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.List;

public class TickerPersister {
    private static final Logger LOG = LogManager.getLogger();

    public static void persistTicker(BarSeries series) throws IOException {
        GsonBarSeries exportableSeries = GsonBarSeries.from(series);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        FileWriter writer = new FileWriter("barData_" + System.currentTimeMillis() + ".json");
        gson.toJson(exportableSeries, writer);
        writer.flush();
        writer.close();
    }

    public static BarSeries loadTicker(String marketName) throws IOException {
        Gson gson = new Gson();

        try {
            FileReader reader = new FileReader("barData_1618526676774.json");
            GsonBarSeries loadedSeries = gson.fromJson(reader, GsonBarSeries.class);
            reader.close();

            BarSeries result = loadedSeries.toBarSeries();
            LOG.info("Bar series '" + result.getName() + "' successfully loaded. Entries: " + result.getBarCount());
            return result;
        } catch (FileNotFoundException e) {
            LOG.warn("Ticker persistence file not found. Create a new ticker series");
            return new BaseBarSeriesBuilder().withName(marketName + "_" + System.currentTimeMillis()).build();
        }


    }
}
