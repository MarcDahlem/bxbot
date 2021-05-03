package com.gazbert.bxbot.strategies.helper;

import com.gazbert.bxbot.strategies.IntelligentTradeTracker;
import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.TradingApiException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

public class IntelligentSellPriceCalculator implements IntelligentStateTracker.OrderPriceCalculator {
    private static final Logger LOG = LogManager.getLogger();
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat( "#.########");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final IntelligentPriceTracker priceTracker;
    private final IntelligentStateTracker stateTracker;
    private final IntelligentSellPriceParameters params;

    public IntelligentSellPriceCalculator(IntelligentPriceTracker priceTracker, IntelligentStateTracker stateTracker, IntelligentSellPriceParameters params) {
        this.priceTracker = priceTracker;
        this.stateTracker = stateTracker;
        this.params = params;
    }

    @Override
    public BigDecimal calculate() throws TradingApiException, ExchangeNetworkException, StrategyException {
        return computeCurrentSellPrice();
    }

    private BigDecimal computeCurrentSellPrice() throws TradingApiException, ExchangeNetworkException {
        BigDecimal breakEven = calculateBreakEven();
        LOG.info(() -> "The calculated break even for selling would be '" + priceTracker.formatWithCounterCurrency(breakEven) + "'");
        BigDecimal maximalPriceLimitAboveBreakEven = calculateMaximalPriceLimitAboveBreakEven(breakEven);
        if (maximalPriceLimitAboveBreakEven.compareTo(breakEven) >= 0) {
            LOG.info(() -> "SELL phase - the above trailing gain is reached. Computed new SELL price is '" + priceTracker.formatWithCounterCurrency(maximalPriceLimitAboveBreakEven) + "' above the break even");
            return maximalPriceLimitAboveBreakEven;
        } else {
            BigDecimal belowBreakEvenPriceLimit = calculateBelowBreakEvenPriceLimit();
            LOG.info(() -> "SELL phase - still below break even. Computed new SELL price is '" + priceTracker.formatWithCounterCurrency(belowBreakEvenPriceLimit) + "' below the break even.");
            return belowBreakEvenPriceLimit;
        }
    }

    private BigDecimal calculateBreakEven() throws TradingApiException, ExchangeNetworkException {
        /* (p1 * (1+f)) / (1-f) <= p2 */
        BigDecimal buyFees = BigDecimal.ONE.add(params.getBuyFee());
        BigDecimal sellFees = BigDecimal.ONE.subtract(params.getSellFee());

        BigDecimal totalBuy = stateTracker.getCurrentBuyOrderPrice().multiply(buyFees);
        BigDecimal estimatedBreakEven = totalBuy.divide(sellFees, 8, RoundingMode.HALF_UP);
        return estimatedBreakEven;
    }

    private BigDecimal calculateBelowBreakEvenPriceLimit() {
        BigDecimal distanceToCurrentMarketPrice = priceTracker.getBid().multiply(params.getCurrentSellStopLimitPercentageBelowBreakEven());
        return priceTracker.getBid().subtract(distanceToCurrentMarketPrice);
    }

    private BigDecimal calculateMaximalPriceLimitAboveBreakEven(BigDecimal breakEven) {
        BigDecimal aboveBreakEvenPriceLimit = calculateAboveBreakEvenPriceLimit();
        BigDecimal minimumAboveBreakEvenPriceLimit = calculateMinimumAboveBreakEvenPriceLimit(breakEven);
        return aboveBreakEvenPriceLimit.max(minimumAboveBreakEvenPriceLimit);
    }

    private BigDecimal calculateAboveBreakEvenPriceLimit() {
        return priceTracker.getBid().subtract(priceTracker.getBid().multiply(params.getCurrentSellStopLimitPercentageAboveBreakEven()));
    }

    private BigDecimal calculateMinimumAboveBreakEvenPriceLimit(BigDecimal breakEven) {
        BigDecimal currentSellStopLimitPercentageMinimumAboveBreakEven = params.getCurrentSellStopLimitPercentageMinimumAboveBreakEven();
        BigDecimal minimalDistanceToCurrentMarketPrice = priceTracker.getBid().subtract(priceTracker.getBid().multiply(currentSellStopLimitPercentageMinimumAboveBreakEven));
        BigDecimal minimumAboveBreakEvenAsFactor = BigDecimal.ONE.subtract(currentSellStopLimitPercentageMinimumAboveBreakEven);
        BigDecimal minimalDistanceNeededToBreakEven = breakEven.divide(minimumAboveBreakEvenAsFactor,8, RoundingMode.HALF_UP);
        return minimalDistanceNeededToBreakEven.min(minimalDistanceToCurrentMarketPrice);

    }

    public void logStatistics() throws TradingApiException, ExchangeNetworkException {
        BigDecimal currentMarketBidPrice = priceTracker.getBid();
        BigDecimal currentSellOrderPrice = stateTracker.getCurrentSellOrderPrice();
        BigDecimal breakEven = calculateBreakEven();

        BigDecimal aboveBreakEvenPriceLimit = calculateAboveBreakEvenPriceLimit();
        BigDecimal belowBreakEvenPriceLimit = calculateBelowBreakEvenPriceLimit();
        BigDecimal minimumAboveBreakEvenPriceLimit = calculateMinimumAboveBreakEvenPriceLimit(breakEven);
        BigDecimal currentBuyOrderPrice = stateTracker.getCurrentBuyOrderPrice();
        BigDecimal percentageChangeToBuyOrder = getPercentageChange(currentMarketBidPrice, currentBuyOrderPrice);
        BigDecimal percentageChangeToBreakEven = getPercentageChange(currentMarketBidPrice, breakEven);
        BigDecimal percentageChangeCurrentSellToMarket = getPercentageChange(currentSellOrderPrice, currentMarketBidPrice);
        BigDecimal percentageChangeCurrentSellToBreakEven = getPercentageChange(currentSellOrderPrice, breakEven);
        BigDecimal percentageChangeCurrentSellToBuy = getPercentageChange(currentSellOrderPrice, currentBuyOrderPrice);

        LOG.info(() -> "Current sell statistics: \n" +
                "######### SELL ORDER STATISTICS #########\n" +
                "current market price (last): " + priceTracker.getFormattedLast() + "\n" +
                "current market price (bid): " + priceTracker.getFormattedBid() + "\n" +
                "current market price (ask): " + priceTracker.getFormattedAsk() + "\n" +
                "current BUY order price: " + priceTracker.formatWithCounterCurrency(currentBuyOrderPrice) + "\n" +
                "current SELL order price: " + priceTracker.formatWithCounterCurrency(currentSellOrderPrice) + "\n" +
                "break even: " + priceTracker.formatWithCounterCurrency(breakEven) + "\n" +
                "-----------------------------------------\n" +
                "market change (bid) to buy price: " + DECIMAL_FORMAT.format(percentageChangeToBuyOrder) + "%\n" +
                "market change (bid) to break even: " + DECIMAL_FORMAT.format(percentageChangeToBreakEven) + "%\n" +
                "current sell price to buy price: " + DECIMAL_FORMAT.format(percentageChangeCurrentSellToBuy) + "%\n" +
                "current sell price to market (bid): " + DECIMAL_FORMAT.format(percentageChangeCurrentSellToMarket) + "%\n" +
                "current sell price to break even: " + DECIMAL_FORMAT.format(percentageChangeCurrentSellToBreakEven) + "%\n" +
                "-----------------------------------------\n" +
                "limit above break even: " + priceTracker.formatWithCounterCurrency(aboveBreakEvenPriceLimit) + "\n" +
                "limit minimum above break even: " + priceTracker.formatWithCounterCurrency(minimumAboveBreakEvenPriceLimit) + "\n" +
                "limit below break even: " + priceTracker.formatWithCounterCurrency(belowBreakEvenPriceLimit) + "\n" +
                "#########################################"

        );
    }

    private BigDecimal getPercentageChange(BigDecimal newPrice, BigDecimal priceToCompareAgainst) {
        return newPrice.subtract(priceToCompareAgainst).divide(priceToCompareAgainst, 10, RoundingMode.HALF_UP).multiply(ONE_HUNDRED);
    }

    public interface IntelligentSellPriceParameters {
        BigDecimal getBuyFee() throws TradingApiException, ExchangeNetworkException;
        BigDecimal getSellFee() throws TradingApiException, ExchangeNetworkException;
        BigDecimal getCurrentSellStopLimitPercentageBelowBreakEven();
        BigDecimal getCurrentSellStopLimitPercentageAboveBreakEven();
        BigDecimal getCurrentSellStopLimitPercentageMinimumAboveBreakEven();
    }
}
