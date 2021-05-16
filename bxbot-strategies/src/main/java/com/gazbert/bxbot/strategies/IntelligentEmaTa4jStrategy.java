package com.gazbert.bxbot.strategies;

import com.gazbert.bxbot.strategies.helper.*;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.TradingApiException;
import com.gazbert.bxbot.trading.api.util.ta4j.Ta4j2Chart;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Indicator;
import org.ta4j.core.Rule;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.indicators.helpers.TransformIndicator;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.Collection;
import java.util.LinkedList;

@Component("intelligentEmaTa4jStrategy") // used to load the strategy using Spring bean injection
public class IntelligentEmaTa4jStrategy extends AbstractIntelligentStrategy {

    private static final DecimalFormat DECIMAL_FORMAT_PERCENTAGE = new DecimalFormat("#.#### %");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private BaseStrategy ta4jStrategy;
    private BigDecimal buyFee;
    private BigDecimal sellFee;


    private Indicator<Num> buyIndicatorLong;
    private Indicator<Num> buyIndicatorShort;
    private Indicator<Num> sellIndicatorLong;
    private Indicator<Num> sellIndicatorShort;
    private StochasticOscillatorKIndicator stochasticOscillaltorK;
    private MACDIndicator macd;

    @Override
    protected void botWillStartup(StrategyConfig config) throws TradingApiException, ExchangeNetworkException {
        initTa4jStrategy();
    }

    private void initTa4jStrategy() throws TradingApiException, ExchangeNetworkException {
        BarSeries series = priceTracker.getSeries();
        ClosePriceIndicator closePriceIndicator = new ClosePriceIndicator(series);

        stochasticOscillaltorK = new StochasticOscillatorKIndicator(series, 140);
        macd = new MACDIndicator(closePriceIndicator, 90, 260);
        EMAIndicator emaMacd = new EMAIndicator(macd, 180);

        BigDecimal buyFeeFactor = BigDecimal.ONE.add(buyFee);
        BigDecimal sellFeeFactor = BigDecimal.ONE.subtract(sellFee);

        buyIndicatorLong = new EMAIndicator(closePriceIndicator, 26);
        buyIndicatorShort = TransformIndicator.multiply(new EMAIndicator(closePriceIndicator, 16), sellFeeFactor);

        sellIndicatorLong = new EMAIndicator(closePriceIndicator, 104);
        sellIndicatorShort = TransformIndicator.multiply(new EMAIndicator(closePriceIndicator, 9), buyFeeFactor);

        Rule entryRule = new CrossedUpIndicatorRule(buyIndicatorShort, buyIndicatorLong) // Trend
                /*.and(new UnderIndicatorRule(stochasticOscillaltorK, 20)) // Signal 1*/;/*.and(new OverIndicatorRule(macd, emaMacd)); // Signal 2*/

        Rule exitRule = new CrossedDownIndicatorRule(sellIndicatorShort, sellIndicatorLong) // Trend
                /*.and(new OverIndicatorRule(stochasticOscillaltorK, 80)) // Signal 1*/;/*.and(new UnderIndicatorRule(macd, emaMacd)); // Signal 2*/
        ta4jStrategy = new BaseStrategy("Intelligent Ta4j", entryRule, exitRule);
    }

    @Override
    protected Collection<Ta4j2Chart.ChartIndicatorConfig> createStrategySpecificLiveChartIndicators() {
        LinkedList<Ta4j2Chart.ChartIndicatorConfig> result = new LinkedList<>();
        result.add(new Ta4j2Chart.ChartIndicatorConfig(buyIndicatorShort, "buy short", Ta4j2Chart.BUY_SHORT_LOOKBACK_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(buyIndicatorLong, "buy long", Ta4j2Chart.BUY_LONG_LOOKBACK_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(sellIndicatorShort, "sell short", Ta4j2Chart.SELL_CURRENT_LIMIT_COLOR));
        result.add(new Ta4j2Chart.ChartIndicatorConfig(sellIndicatorLong, "sell long", Ta4j2Chart.SELL_LIMIT_1_COLOR));
        return result;
    }

    @Override
    protected IntelligentStateTracker.OrderPriceCalculator createSellPriceCalculator(StrategyConfig config) throws TradingApiException, ExchangeNetworkException {
        buyFee = tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(market.getId());
        sellFee = tradingApi.getPercentageOfSellOrderTakenForExchangeFee(market.getId());
        return new IntelligentSellPriceCalculator(priceTracker, stateTracker, new StaticSellPriceParams(buyFee, sellFee, config));
    }

    @Override
    protected IntelligentStateTracker.OrderPriceCalculator createBuyPriceCalculator(StrategyConfig config) {
        IntelligentStateTracker.OrderPriceCalculator result = new IntelligentBuyPriceCalculator(market, priceTracker, config);
        //result = new StaticBuyPriceCalculator(market, priceTracker, new BigDecimal("25")); // TODO remove
        return result;
    }

    @Override
    protected IntelligentStateTracker.OnTradeSuccessfullyClosedListener createTradesObserver(StrategyConfig config) {
        return new IntelligentTradeTracker();
    }

    @Override
    protected boolean marketMovedUp() {
        boolean result = ta4jStrategy.shouldEnter(priceTracker.getSeries().getEndIndex());
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
                    "\n* Place a BUY order?: " + result +
                    "\n#############################";
        });
        return result;
    }

    private Num getPercentageChange(Num newPrice, Num priceToCompareAgainst) {
        return newPrice.minus(priceToCompareAgainst).dividedBy(priceToCompareAgainst);
    }

    @Override
    protected boolean marketMovedDown() throws TradingApiException, ExchangeNetworkException {
        boolean result = ta4jStrategy.shouldExit(priceTracker.getSeries().getEndIndex());
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
                "\n* Place a SELL order?: " + result +
                "\n###############################");
        return result;
    }

    @Override
    protected Collection<? extends Ta4j2Chart.ChartIndicatorConfig> createStrategySpecificOverviewChartIndicators() throws TradingApiException, ExchangeNetworkException {
        Collection<Ta4j2Chart.ChartIndicatorConfig> indicators = createStrategySpecificLiveChartIndicators();

        Ta4j2Chart.YAxisGroupConfig macdYAxisConfig = new Ta4j2Chart.YAxisGroupConfig("macd", 1, Ta4j2Chart.AREA_COLOR_1);
        indicators.add(new Ta4j2Chart.ChartIndicatorConfig(macd, "macd", Ta4j2Chart.AREA_COLOR_LINE_1, macdYAxisConfig));

        Ta4j2Chart.YAxisGroupConfig osciKYAxisConfig = new Ta4j2Chart.YAxisGroupConfig("osci k", 2, Ta4j2Chart.AREA_COLOR_2);
        indicators.add(new Ta4j2Chart.ChartIndicatorConfig(stochasticOscillaltorK, "stoch osci k", Ta4j2Chart.AREA_COLOR_LINE_2, osciKYAxisConfig));
        return indicators;
    }

    @Override
    protected void botWillShutdown() {
    }
}
