package com.gazbert.bxbot.strategies;

import com.gazbert.bxbot.strategies.helper.*;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.TradingApiException;
import com.gazbert.bxbot.trading.api.util.ta4j.BreakEvenIndicator;
import com.gazbert.bxbot.trading.api.util.ta4j.BuyAndSellSignalsToChart;
import com.gazbert.bxbot.trading.api.util.ta4j.RecordedStrategy;
import org.springframework.stereotype.Component;
import org.ta4j.core.*;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;
import org.ta4j.core.indicators.helpers.*;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;
import org.ta4j.core.rules.OverIndicatorRule;
import org.ta4j.core.rules.UnderIndicatorRule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.*;

@Component("intelligentTa4jStrategy") // used to load the strategy using Spring bean injection
public class IntelligentTa4jStrategy extends AbstractIntelligentStrategy {

    private static final DecimalFormat DECIMAL_FORMAT_PERCENTAGE = new DecimalFormat( "#.#### %");
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

    private void initTa4jStrategy() throws TradingApiException, ExchangeNetworkException {
        BarSeries series = priceTracker.getSeries();
        buyFee =tradingApi.getPercentageOfBuyOrderTakenForExchangeFee(market.getId());
        sellFee = tradingApi.getPercentageOfSellOrderTakenForExchangeFee(market.getId());

        ClosePriceIndicator closePriceIndicator = new ClosePriceIndicator(series);
        LowPriceIndicator bidPriceIndicator = new LowPriceIndicator(series);
        HighPriceIndicator askPriceIndicator = new HighPriceIndicator(series);

        stochasticOscillaltorK = new StochasticOscillatorKIndicator(series, 140);
        macd = new MACDIndicator(closePriceIndicator, 90, 260);
        EMAIndicator emaMacd = new EMAIndicator(macd, 180);

        BigDecimal buyFeeFactor = BigDecimal.ONE.add(buyFee);
        BigDecimal sellFeeFactor = BigDecimal.ONE.subtract(sellFee);

        buyIndicatorLong = new EMAIndicator(bidPriceIndicator, 26);
        buyIndicatorShort = TransformIndicator.multiply(new EMAIndicator(bidPriceIndicator, 9), sellFeeFactor);

        sellIndicatorLong = new EMAIndicator(askPriceIndicator, 104);
        sellIndicatorShort = TransformIndicator.multiply(new EMAIndicator(askPriceIndicator, 9), buyFeeFactor);

        Rule entryRule = new CrossedUpIndicatorRule(buyIndicatorShort, buyIndicatorLong) // Trend
                /*.and(new UnderIndicatorRule(stochasticOscillaltorK, 20)) // Signal 1*/
                ;/*.and(new OverIndicatorRule(macd, emaMacd)); // Signal 2*/

        Rule exitRule = new CrossedDownIndicatorRule(sellIndicatorShort, sellIndicatorLong) // Trend
                /*.and(new OverIndicatorRule(stochasticOscillaltorK, 80)) // Signal 1*/
                ;/*.and(new UnderIndicatorRule(macd, emaMacd)); // Signal 2*/
        ta4jStrategy = new BaseStrategy("Intelligent Ta4j", entryRule, exitRule);
    }

    @Override
    protected void botWillShutdown() throws TradingApiException, ExchangeNetworkException {
        RecordedStrategy recordedStrategy = stateTracker.getRecordedStrategy();
        Map<Indicator<Num>, String> indicators = new HashMap<>();
        indicators.put(buyIndicatorShort, "buy short");
        indicators.put(buyIndicatorLong, "buy long");
        indicators.put(sellIndicatorShort, "sell short");
        indicators.put(sellIndicatorLong, "sell long");
        indicators.put(macd, "macd");
        indicators.put(stochasticOscillaltorK, "stoch osci k");
        indicators.putAll(recordedStrategy.getIndicators());

        BuyAndSellSignalsToChart.printSeries(priceTracker.getSeries(), recordedStrategy, indicators);
    }

    @Override
    protected IntelligentStateTracker.OrderPriceCalculator createSellPriceCalculator(StrategyConfig config) {
        BigDecimal configuredSellStopLimitPercentageBelowBreakEven = StrategyConfigParser.readPercentageConfigValue(config, "sell-stop-limit-percentage-below-break-even");
        BigDecimal configuredSellStopLimitPercentageAboveBreakEven = StrategyConfigParser.readPercentageConfigValue(config, "sell-stop-limit-percentage-above-break-even");
        BigDecimal configuredSellStopLimitPercentageMinimumAboveBreakEven = StrategyConfigParser.readPercentageConfigValue(config, "sell-stop-limit-percentage-minimum-above-break-even");
        return new IntelligentSellPriceCalculator(priceTracker, new IntelligentSellPriceCalculator.IntelligentSellPriceParameters() {


            @Override
            public BigDecimal getBuyFee() throws TradingApiException, ExchangeNetworkException {
                return buyFee;
            }

            @Override
            public BigDecimal getSellFee() throws TradingApiException, ExchangeNetworkException {
                return sellFee;
            }

            @Override
            public BigDecimal getCurrentBuyOrderPrice() {
                return stateTracker.getCurrentBuyOrderPrice();
            }

            @Override
            public BigDecimal getCurrentSellOrderPrice() {
                return stateTracker.getCurrentSellOrderPrice();
            }

            @Override
            public BigDecimal getCurrentSellStopLimitPercentageBelowBreakEven() {
                return configuredSellStopLimitPercentageBelowBreakEven;
            }

            @Override
            public BigDecimal getCurrentSellStopLimitPercentageAboveBreakEven() {
                return configuredSellStopLimitPercentageAboveBreakEven;
            }

            @Override
            public BigDecimal getCurrentSellStopLimitPercentageMinimumAboveBreakEven() {
                return configuredSellStopLimitPercentageMinimumAboveBreakEven;
            }
        });
    }

    @Override
    protected IntelligentStateTracker.OrderPriceCalculator createBuyPriceCalculator(StrategyConfig config) {
        IntelligentStateTracker.OrderPriceCalculator result = new IntelligentBuyPriceCalculator(market, priceTracker, config);
        result = new StaticBuyPriceCalculator(market, priceTracker, new BigDecimal("25")); // TODO remove
        return result;
    }

    @Override
    protected IntelligentStateTracker.OnTradeSuccessfullyClosedListener createTradesObserver(StrategyConfig config) {
        return new IntelligentTradeTracker();
    }

    @Override
    protected boolean marketMovedUp() throws TradingApiException, ExchangeNetworkException {
        if (ta4jStrategy == null) {
            initTa4jStrategy();
        }
        boolean result = ta4jStrategy.shouldEnter(priceTracker.getSeries().getEndIndex());
        LOG.info(() -> {
            Num currentLongEma = buyIndicatorLong.getValue(priceTracker.getSeries().getEndIndex());
            Num currentShortEma = buyIndicatorShort.getValue(priceTracker.getSeries().getEndIndex());
            return market.getName() +
                    "\n######### MOVED UP? #########\n" +
                    "* Current ask price: " + priceTracker.getFormattedAsk() +
                    "\n* Current long EMA value: " + priceTracker.formatWithCounterCurrency((BigDecimal) currentLongEma.getDelegate()) +
                    "\n* Current short EMA value: " + priceTracker.formatWithCounterCurrency((BigDecimal) currentShortEma.getDelegate()) +
                    "\n* Percentage EMA gain needed: " + DECIMAL_FORMAT_PERCENTAGE.format((BigDecimal)getPercentageChange(currentLongEma, currentShortEma).getDelegate()) +
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
        if (ta4jStrategy == null) {
            initTa4jStrategy();
        }
        boolean result = ta4jStrategy.shouldExit(priceTracker.getSeries().getEndIndex());
        LOG.info(() -> {
            Num currentLongEma = sellIndicatorLong.getValue(priceTracker.getSeries().getEndIndex());
            Num currentShortEma = sellIndicatorShort.getValue(priceTracker.getSeries().getEndIndex());
            return market.getName() +
                    "\n######### MOVED DOWN? #########\n" +
                    "* Current bid price: " + priceTracker.getFormattedBid() +
                    "\n* Current long EMA value: " + priceTracker.formatWithCounterCurrency((BigDecimal) currentLongEma.getDelegate()) +
                    "\n* Current short EMA value: " + priceTracker.formatWithCounterCurrency((BigDecimal) currentShortEma.getDelegate()) +
                    "\n* Percentage EMA loss needed: " + DECIMAL_FORMAT_PERCENTAGE.format((BigDecimal)getPercentageChange(currentLongEma, currentShortEma).getDelegate()) +
                    "\n* Absolute EMA loss needed: " + priceTracker.formatWithCounterCurrency((BigDecimal) currentLongEma.minus(currentShortEma).getDelegate()) +
                    "\n* Place a SELL order?: " + result +
                    "\n###############################";
        });
        return result;
    }
}
