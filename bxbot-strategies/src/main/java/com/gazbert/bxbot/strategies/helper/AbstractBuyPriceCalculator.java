package com.gazbert.bxbot.strategies.helper;

import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.Market;
import com.gazbert.bxbot.trading.api.TradingApiException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

public abstract class AbstractBuyPriceCalculator implements IntelligentStateTracker.OrderPriceCalculator {
    protected static final Logger LOG = LogManager.getLogger();
    protected static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat( "#.########");
    protected final Market market;
    protected final IntelligentPriceTracker priceTracker;

    protected AbstractBuyPriceCalculator(Market market, IntelligentPriceTracker priceTracker) {
        this.market = market;
        this.priceTracker = priceTracker;
    }

    private BigDecimal getAmountOfPiecesToBuy() throws TradingApiException, ExchangeNetworkException, StrategyException {
        final BigDecimal balanceToUseForBuyOrder = getBalanceToUseForBuyOrder();
        LOG.info(() ->market.getName()+ " Calculating amount of base currency ("+ market.getBaseCurrency()+ ") to buy for amount of counter currency "+ priceTracker.formatWithCounterCurrency(balanceToUseForBuyOrder));

        /*
         * Most exchanges (if not all) use 8 decimal places and typically round in favour of the
         * exchange. It's usually safest to round down the order quantity in your calculations.
         */
        final BigDecimal amountOfPiecesInBaseCurrencyToBuy = balanceToUseForBuyOrder.divide(priceTracker.getLast(), 8, RoundingMode.HALF_DOWN);

        LOG.info(() ->market.getName()+ " Amount of base currency ("+ market.getBaseCurrency()+ ") to BUY for "  + priceTracker.formatWithCounterCurrency(balanceToUseForBuyOrder)
                + " based on last market trade price: "+ amountOfPiecesInBaseCurrencyToBuy);

        return amountOfPiecesInBaseCurrencyToBuy;
    }

    @Override
    public BigDecimal calculate() throws TradingApiException, ExchangeNetworkException, StrategyException {
        return getAmountOfPiecesToBuy();
    }

    @Override
    public void logStatistics() throws TradingApiException, ExchangeNetworkException, StrategyException {
        LOG.info("Current buy statistics: \n" +
                "######### BUY PRICE STATISTICS #########\n" +
                "current amount used for buy orders: " + priceTracker.formatWithCounterCurrency(getBalanceToUseForBuyOrder())+ "\n" +
                "current amount of pieces to buy: " + DECIMAL_FORMAT.format(getAmountOfPiecesToBuy())+ "\n" +
                "current market price: " + priceTracker.getLast()+ "\n" +
                "#########################################"
        );
    }

    protected abstract BigDecimal getBalanceToUseForBuyOrder() throws ExchangeNetworkException, TradingApiException, StrategyException ;
}
