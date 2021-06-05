package com.gazbert.bxbot.trading.api.util.ta4j;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.AbstractIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.num.Num;

import static org.ta4j.core.num.NaN.NaN;

public class ReversalPointsIndicator extends AbstractIndicator<Num> {

    private final HighPriceIndicator highPriceIndicator;
    private final LowPriceIndicator lowPriceIndicator;
    private final Cache<Bar, ReversalComputationState> savedStates;

    public enum ReversalType {
        LOWS, HIGHS;
    }

    private final ReversalType type;
    private final Indicator<Num> valueIndicator;
    private final TreeSet<Integer> lows = new TreeSet<>();
    private final TreeSet<Integer> highs = new TreeSet<>();

    public ReversalPointsIndicator(BarSeries series, ReversalType type) {
        this(series, type, null);
    }

    public ReversalPointsIndicator(BarSeries series, ReversalType type, Indicator<Num> valueIndicator) {
        super(series);
        this.type = type;
        this.valueIndicator = valueIndicator;
        this.highPriceIndicator = new HighPriceIndicator(series);
        this.lowPriceIndicator = new LowPriceIndicator(series);
        savedStates = CacheBuilder.newBuilder().maximumSize(10).build();
    }

    @Override
    public Num getValue(int index) {
        updateReversals();
        switch (type) {
            case LOWS:
                Integer lastLowIndex = lows.floor(index);
                if (lastLowIndex == null) {
                    return NaN;
                }
                return this.valueIndicator == null ? this.lowPriceIndicator.getValue(lastLowIndex) : this.valueIndicator.getValue(lastLowIndex);
            case HIGHS:
                Integer lastHighIndex = highs.floor(index);
                if (lastHighIndex == null) {
                    return NaN;
                }
                return this.valueIndicator == null ? this.highPriceIndicator.getValue(lastHighIndex) : this.valueIndicator.getValue(lastHighIndex);
            default:
                throw new IllegalStateException("Unknown type encountered " + type);
        }
    }

    private void updateReversals() {
        lows.clear();
        highs.clear();

        Bar currentBar = getBarSeries().getLastBar();
        ReversalComputationState state = null;
        try {
            state = savedStates.get(currentBar, () -> {
                ReversalComputationState result = new ReversalComputationState(highPriceIndicator, lowPriceIndicator);
                for (int index = getBarSeries().getEndIndex(); index >= getBarSeries().getBeginIndex(); index--) {
                    result.update(index);
                }
                return result;
            }
            );
        } catch (ExecutionException e) {
            throw new IllegalStateException("Somehint stupid happened: ", e);
        }
        savedStates.put(currentBar, state);

        lows.addAll(state.getLows());
        highs.addAll(state.getHighs());
    }
}
