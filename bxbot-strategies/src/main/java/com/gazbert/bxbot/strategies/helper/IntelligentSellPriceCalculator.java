package com.gazbert.bxbot.strategies.helper;

import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.TradingApiException;
import com.gazbert.bxbot.trading.api.util.ta4j.MarketEnterType;
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
    public BigDecimal calculate(MarketEnterType type) throws TradingApiException, ExchangeNetworkException, StrategyException {
        return computeCurrentSellPrice(type);
    }

    private BigDecimal computeCurrentSellPrice(MarketEnterType type) throws TradingApiException, ExchangeNetworkException, StrategyException {
        if (!type.equals(MarketEnterType.LONG_POSITION)) {
            throw new StrategyException("Short intelligent sell price calculations are not implemented so far");
        }
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

        BigDecimal totalBuy = stateTracker.getCurrentEnterOrderPrice().multiply(buyFees);
        BigDecimal estimatedBreakEven = totalBuy.divide(sellFees, 8, RoundingMode.HALF_UP);
        return estimatedBreakEven;
    }

    private BigDecimal calculateBelowBreakEvenPriceLimit() {
        BigDecimal distanceToCurrentMarketPrice = priceTracker.getLast().multiply(params.getCurrentSellStopLimitPercentageBelowBreakEven());
        return priceTracker.getLast().subtract(distanceToCurrentMarketPrice);
    }

    private BigDecimal calculateMaximalPriceLimitAboveBreakEven(BigDecimal breakEven) {
        BigDecimal aboveBreakEvenPriceLimit = calculateAboveBreakEvenPriceLimit();
        BigDecimal minimumAboveBreakEvenPriceLimit = calculateMinimumAboveBreakEvenPriceLimit(breakEven);
        return aboveBreakEvenPriceLimit.max(minimumAboveBreakEvenPriceLimit);
    }

    private BigDecimal calculateAboveBreakEvenPriceLimit() {
        return priceTracker.getLast().subtract(priceTracker.getLast().multiply(params.getCurrentSellStopLimitPercentageAboveBreakEven()));
    }

    private BigDecimal calculateMinimumAboveBreakEvenPriceLimit(BigDecimal breakEven) {
        BigDecimal currentSellStopLimitPercentageMinimumAboveBreakEven = params.getCurrentSellStopLimitPercentageMinimumAboveBreakEven();
        BigDecimal minimalDistanceToCurrentMarketPrice = priceTracker.getLast().subtract(priceTracker.getLast().multiply(currentSellStopLimitPercentageMinimumAboveBreakEven));
        BigDecimal minimumAboveBreakEvenAsFactor = BigDecimal.ONE.subtract(currentSellStopLimitPercentageMinimumAboveBreakEven);
        BigDecimal minimalDistanceNeededToBreakEven = breakEven.divide(minimumAboveBreakEvenAsFactor,8, RoundingMode.HALF_UP);
        return minimalDistanceNeededToBreakEven.min(minimalDistanceToCurrentMarketPrice);

    }

    @Override
    public void logStatistics(MarketEnterType type) throws TradingApiException, ExchangeNetworkException {
        BigDecimal currentMarketPrice = priceTracker.getLast();
        BigDecimal currentSellOrderPrice = stateTracker.getCurrentExitOrderPrice();
        BigDecimal breakEven = calculateBreakEven();

        BigDecimal aboveBreakEvenPriceLimit = calculateAboveBreakEvenPriceLimit();
        BigDecimal belowBreakEvenPriceLimit = calculateBelowBreakEvenPriceLimit();
        BigDecimal minimumAboveBreakEvenPriceLimit = calculateMinimumAboveBreakEvenPriceLimit(breakEven);
        BigDecimal currentBuyOrderPrice = stateTracker.getCurrentEnterOrderPrice();
        BigDecimal percentageChangeToBuyOrder = getPercentageChange(currentMarketPrice, currentBuyOrderPrice);
        BigDecimal percentageChangeToBreakEven = getPercentageChange(currentMarketPrice, breakEven);
        BigDecimal percentageChangeCurrentSellToMarket = getPercentageChange(currentSellOrderPrice, currentMarketPrice);
        BigDecimal percentageChangeCurrentSellToBreakEven = getPercentageChange(currentSellOrderPrice, breakEven);
        BigDecimal percentageChangeCurrentSellToBuy = getPercentageChange(currentSellOrderPrice, currentBuyOrderPrice);

        LOG.info(() -> "Current sell statistics: \n" +
                "######### SELL ORDER STATISTICS #########\n" +
                "current market price (last): " + priceTracker.getFormattedLast() + "\n" +
                "current BUY order price: " + priceTracker.formatWithCounterCurrency(currentBuyOrderPrice) + "\n" +
                "current SELL order price: " + priceTracker.formatWithCounterCurrency(currentSellOrderPrice) + "\n" +
                "break even: " + priceTracker.formatWithCounterCurrency(breakEven) + "\n" +
                "-----------------------------------------\n" +
                "market change (last) to buy price: " + DECIMAL_FORMAT.format(percentageChangeToBuyOrder) + "%\n" +
                "market change (last) to break even: " + DECIMAL_FORMAT.format(percentageChangeToBreakEven) + "%\n" +
                "current sell price to buy price: " + DECIMAL_FORMAT.format(percentageChangeCurrentSellToBuy) + "%\n" +
                "current sell price to market (last): " + DECIMAL_FORMAT.format(percentageChangeCurrentSellToMarket) + "%\n" +
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
