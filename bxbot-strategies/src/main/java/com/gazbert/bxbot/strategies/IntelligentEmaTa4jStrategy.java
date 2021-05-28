package com.gazbert.bxbot.strategies;

import static com.gazbert.bxbot.trading.api.util.ta4j.MarketEnterType.LONG_POSITION;
import static com.gazbert.bxbot.trading.api.util.ta4j.MarketEnterType.SHORT_POSITION;

import com.gazbert.bxbot.strategies.helper.IntelligentEnterPriceCalculator;
import com.gazbert.bxbot.strategies.helper.IntelligentSellPriceCalculator;
import com.gazbert.bxbot.strategies.helper.IntelligentStateTracker;
import com.gazbert.bxbot.trading.api.util.ta4j.MarketEnterType;
import com.gazbert.bxbot.strategies.helper.StaticSellPriceParams;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.TradingApiException;
import com.gazbert.bxbot.trading.api.util.ta4j.Ta4j2Chart;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Indicator;
import org.ta4j.core.Rule;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.ConstantIndicator;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;
import org.ta4j.core.rules.OverIndicatorRule;
import org.ta4j.core.rules.UnderIndicatorRule;

@Component("intelligentEmaTa4jStrategy") // used to load the strategy using Spring bean injection
public class IntelligentEmaTa4jStrategy extends AbstractIntelligentStrategy {

    private static final DecimalFormat DECIMAL_FORMAT_PERCENTAGE = new DecimalFormat("#.#### %");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private BaseStrategy ta4jStrategyLong;
    private BigDecimal buyFee;
    private BigDecimal sellFee;


    private Indicator<Num> buyIndicatorLong;
    private Indicator<Num> buyIndicatorShort;
    private Indicator<Num> sellIndicatorLong;
    private Indicator<Num> sellIndicatorShort;
    private StochasticOscillatorKIndicator stochasticOscillaltorK;
    private MACDIndicator macd;
    private BaseStrategy ta4jStrategyShort;
    private EMAIndicator emaMacd;

    @Override
    protected void botWillStartup(StrategyConfig config) throws TradingApiException, ExchangeNetworkException {
        initTa4jStrategy();
    }

    private void initTa4jStrategy() throws TradingApiException, ExchangeNetworkException {
        BarSeries series = priceTracker.getSeries();
        ClosePriceIndicator closePriceIndicator = new ClosePriceIndicator(series);
        int i = 20;
        int j = 6;
        int k = 6;
        int l = 6;

        stochasticOscillaltorK = new StochasticOscillatorKIndicator(series, k); // 14
        macd = new MACDIndicator(closePriceIndicator, j, i); // 9, 26
        emaMacd = new EMAIndicator(macd, l); // 18

        BigDecimal buyFeeFactor = BigDecimal.ONE.add(buyFee);
        BigDecimal sellFeeFactor = BigDecimal.ONE.subtract(sellFee);

        buyIndicatorLong = new EMAIndicator(closePriceIndicator, i);
        buyIndicatorShort = new EMAIndicator(closePriceIndicator, j);

        sellIndicatorLong = new EMAIndicator(closePriceIndicator, i);
        sellIndicatorShort = new EMAIndicator(closePriceIndicator, j);

        Rule entryRule = new OverIndicatorRule(buyIndicatorShort, buyIndicatorLong) // Trend
                .and(new CrossedDownIndicatorRule(stochasticOscillaltorK, 20)) // Signal 1
                .and(new OverIndicatorRule(macd, emaMacd)) // Signal 2
                ;

        Rule exitRule = new UnderIndicatorRule(sellIndicatorShort, sellIndicatorLong) // Trend
                .and(new CrossedUpIndicatorRule(stochasticOscillaltorK, 80)) // Signal 1
                .and(new UnderIndicatorRule(macd, emaMacd)) // Signal 2
                ;
        ta4jStrategyLong = new BaseStrategy("Intelligent Ta4j EMA MACD (long)", entryRule, exitRule);
        ta4jStrategyShort = new BaseStrategy("Intelligent Ta4j EMA MACD (short)", exitRule, entryRule);
    }

    @Override
    protected Collection<Ta4j2Chart.ChartIndicatorConfig> createStrategySpecificLiveChartIndicators() {
        LinkedList<Ta4j2Chart.ChartIndicatorConfig> result = new LinkedList<>();
        Ta4j2Chart.YAxisGroupConfig macdYAxisConfig = new Ta4j2Chart.YAxisGroupConfig("macd", 1, Ta4j2Chart.AREA_COLOR_1);
        result.add(new Ta4j2Chart.ChartIndicatorConfig(macd, "macd", Ta4j2Chart.AREA_COLOR_LINE_1, macdYAxisConfig));

        Ta4j2Chart.YAxisGroupConfig osciKYAxisConfig = new Ta4j2Chart.YAxisGroupConfig("osci k", 2, Ta4j2Chart.AREA_COLOR_2);
        result.add(new Ta4j2Chart.ChartIndicatorConfig(stochasticOscillaltorK, "stoch osci k", Ta4j2Chart.AREA_COLOR_LINE_2, osciKYAxisConfig));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(
                new ConstantIndicator<>(priceTracker.getSeries(), priceTracker.getSeries().numOf(80)),
                "stoch osci k (80)",
                Ta4j2Chart.AREA_COLOR_LINE_2,
                osciKYAxisConfig));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(
                new ConstantIndicator<>(priceTracker.getSeries(), priceTracker.getSeries().numOf(70)),
                "stoch osci k (70)",
                Ta4j2Chart.AREA_COLOR_LINE_2,
                osciKYAxisConfig));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(
                new ConstantIndicator<>(priceTracker.getSeries(), priceTracker.getSeries().numOf(30)),
                "stoch osci k (30)",
                Ta4j2Chart.AREA_COLOR_LINE_2,
                osciKYAxisConfig));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(
                new ConstantIndicator<>(priceTracker.getSeries(), priceTracker.getSeries().numOf(20)),
                "stoch osci k (20)",
                Ta4j2Chart.AREA_COLOR_LINE_2,
                osciKYAxisConfig));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(buyIndicatorShort, "buy short", Ta4j2Chart.BUY_SHORT_LOOKBACK_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(buyIndicatorLong, "buy long", Ta4j2Chart.BUY_LONG_LOOKBACK_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(sellIndicatorShort, "sell short", Ta4j2Chart.SELL_CURRENT_LIMIT_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(sellIndicatorLong, "sell long", Ta4j2Chart.SELL_LIMIT_1_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(emaMacd, "emaMacd", Ta4j2Chart.ASK_PRICE_COLOR, macdYAxisConfig));

        return result;
    }

    @Override
    protected IntelligentStateTracker.OrderPriceCalculator createExitPriceCalculator(StrategyConfig config) throws TradingApiException, ExchangeNetworkException {
        buyFee = tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(market.getId());
        sellFee = tradingApi.getPercentageOfSellOrderTakenForExchangeFee(market.getId());
        return new IntelligentSellPriceCalculator(priceTracker, stateTracker, new StaticSellPriceParams(buyFee, sellFee, config));
    }

    @Override
    protected IntelligentStateTracker.OrderPriceCalculator createEnterPriceCalculator(StrategyConfig config) {
        IntelligentStateTracker.OrderPriceCalculator result = new IntelligentEnterPriceCalculator(market, priceTracker, config);
        return result;
    }

    @Override
    protected IntelligentStateTracker.OnTradeSuccessfullyClosedListener createTradesObserver(StrategyConfig config) {
        return new IntelligentTradeTracker();
    }

    @Override
    protected Optional<MarketEnterType> shouldEnterMarket() {
        boolean resultLong = ta4jStrategyLong.shouldEnter(priceTracker.getSeries().getEndIndex() - 1);
        boolean resultShort = ta4jStrategyShort.shouldEnter(priceTracker.getSeries().getEndIndex() - 1);
        LOG.info(() -> {
            Num currentLongEma = buyIndicatorLong.getValue(priceTracker.getSeries().getEndIndex());
            Num currentShortEma = buyIndicatorShort.getValue(priceTracker.getSeries().getEndIndex());
            return market.getName() +
                    "\n######### MOVED UP? #########\n" +
                    "* Current market price: " + priceTracker.getFormattedLast() +
                    "\n* Current long EMA value: " + priceTracker.formatWithCounterCurrency((BigDecimal) currentLongEma.getDelegate()) +
                    "\n* Current short EMA value: " + priceTracker.formatWithCounterCurrency((BigDecimal) currentShortEma.getDelegate()) +
                    "\n* Percentage EMA gain needed: " + DECIMAL_FORMAT_PERCENTAGE.format((BigDecimal) getPercentageChange(currentLongEma, currentShortEma).getDelegate()) +
                    "\n* Absolute EMA gain needed: " + priceTracker.formatWithCounterCurrency((BigDecimal) currentLongEma.minus(currentShortEma).getDelegate()) +
                    "\n* Place a BUY order? (long): " + resultLong +
                    "\n* Place a SELL order? (short): " + resultShort +
                    "\n#############################";
        });
        if (resultLong) {
            return Optional.of(LONG_POSITION);
        }
        return resultShort ?  Optional.of(SHORT_POSITION): Optional.empty();
    }

    private Num getPercentageChange(Num newPrice, Num priceToCompareAgainst) {
        return newPrice.minus(priceToCompareAgainst).dividedBy(priceToCompareAgainst);
    }

    @Override
    protected boolean shouldExitMarket() throws TradingApiException, ExchangeNetworkException {
        boolean resultLong = ta4jStrategyLong.shouldExit(priceTracker.getSeries().getEndIndex() - 1);
        boolean resultShort = ta4jStrategyLong.shouldExit(priceTracker.getSeries().getEndIndex() - 1);
        Num currentLongEma = sellIndicatorLong.getValue(priceTracker.getSeries().getEndIndex());
        Num currentShortEma = sellIndicatorShort.getValue(priceTracker.getSeries().getEndIndex());
        LOG.info(market.getName() +
                "\n######### MOVED DOWN? #########\n" +
                "* Current market price: " + priceTracker.getFormattedLast() +
                "\n Break even: " + priceTracker.formatWithCounterCurrency((BigDecimal) stateTracker.getBreakEvenIndicator().getValue(priceTracker.getSeries().getEndIndex()).getDelegate()) +
                "\n market change (last) to break even: " + DECIMAL_FORMAT_PERCENTAGE.format((BigDecimal) getPercentageChange(priceTracker.getSeries().numOf(priceTracker.getLast()), stateTracker.getBreakEvenIndicator().getValue(priceTracker.getSeries().getEndIndex())).getDelegate()) +
                "\n* Current long EMA value: " + priceTracker.formatWithCounterCurrency((BigDecimal) currentLongEma.getDelegate()) +
                "\n* Current short EMA value: " + priceTracker.formatWithCounterCurrency((BigDecimal) currentShortEma.getDelegate()) +
                "\n* Percentage EMA loss needed: " + DECIMAL_FORMAT_PERCENTAGE.format((BigDecimal) getPercentageChange(currentLongEma, currentShortEma).getDelegate()) +
                "\n* Absolute EMA loss needed: " + priceTracker.formatWithCounterCurrency((BigDecimal) currentLongEma.minus(currentShortEma).getDelegate()) +
                "\n* Place a SELL (long) order?: " + resultLong +
                "\n* Place a BUY (short) order?: " + resultShort +
                "\n###############################");

        switch(stateTracker.getCurrentMarketEntry()) {
            case SHORT_POSITION:
                return resultShort;
            case LONG_POSITION:
                return resultLong;
            default:
                throw new IllegalStateException("Unkown entry type encountered: " + stateTracker.getCurrentMarketEntry());
        }
    }

    @Override
    protected Collection<? extends Ta4j2Chart.ChartIndicatorConfig> createStrategySpecificOverviewChartIndicators() throws TradingApiException, ExchangeNetworkException {
        Collection<Ta4j2Chart.ChartIndicatorConfig> indicators = createStrategySpecificLiveChartIndicators();


        return indicators;
    }

    @Override
    protected void botWillShutdown() {
    }
}
