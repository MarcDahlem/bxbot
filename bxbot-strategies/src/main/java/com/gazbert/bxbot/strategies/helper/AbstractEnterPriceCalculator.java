package com.gazbert.bxbot.strategies.helper;

import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.Market;
import com.gazbert.bxbot.trading.api.TradingApiException;
import com.gazbert.bxbot.trading.api.util.ta4j.MarketEnterType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

public abstract class AbstractEnterPriceCalculator implements IntelligentStateTracker.OrderPriceCalculator {
    protected static final Logger LOG = LogManager.getLogger();
    protected static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat( "#.########");
    protected final Market market;
    protected final IntelligentPriceTracker priceTracker;

    protected AbstractEnterPriceCalculator(Market market, IntelligentPriceTracker priceTracker) {
        this.market = market;
        this.priceTracker = priceTracker;
    }

    private BigDecimal getAmountOfPiecesForEnter(MarketEnterType enterType) throws TradingApiException, ExchangeNetworkException, StrategyException {
        final BigDecimal balanceToUseForEnterOrder = getBalanceToUseForEnterOrder(enterType);
        LOG.info(() ->market.getName()+ " Calculating amount of base currency ("+ market.getBaseCurrency()+ ") to enter for amount of counter currency "+ priceTracker.formatWithCounterCurrency(balanceToUseForEnterOrder));

        /*
         * Most exchanges (if not all) use 8 decimal places and typically round in favour of the
         * exchange. It's usually safest to round down the order quantity in your calculations.
         */
        final BigDecimal amountOfPiecesInBaseCurrencyToEnterMarket = balanceToUseForEnterOrder.divide(priceTracker.getLast(), 8, RoundingMode.HALF_DOWN);

        LOG.info(() ->market.getName()+ " Amount of base currency ("+ market.getBaseCurrency()+ ") to ENTER for "  + priceTracker.formatWithCounterCurrency(balanceToUseForEnterOrder)
                + " based on last market trade price: "+ amountOfPiecesInBaseCurrencyToEnterMarket);

        return amountOfPiecesInBaseCurrencyToEnterMarket;
    }

    @Override
    public BigDecimal calculate(MarketEnterType enterType) throws TradingApiException, ExchangeNetworkException, StrategyException {
        return getAmountOfPiecesForEnter(enterType);
    }

    @Override
    public void logStatistics(MarketEnterType marketEnterType) throws TradingApiException, ExchangeNetworkException, StrategyException {
        LOG.info("Current ENTER statistics: \n" +
                "######### ENTER PRICE STATISTICS #########\n" +
                "current amount used for ENTER orders: " + priceTracker.formatWithCounterCurrency(getBalanceToUseForEnterOrder(marketEnterType))+ "\n" +
                "current amount of pieces to ENTER: " + DECIMAL_FORMAT.format(getAmountOfPiecesForEnter(marketEnterType))+ "\n" +
                "current market price: " + priceTracker.getLast()+ "\n" +
                "#########################################"
        );
    }

    protected abstract BigDecimal getBalanceToUseForEnterOrder(MarketEnterType marketEnterType) throws ExchangeNetworkException, TradingApiException, StrategyException ;
}
