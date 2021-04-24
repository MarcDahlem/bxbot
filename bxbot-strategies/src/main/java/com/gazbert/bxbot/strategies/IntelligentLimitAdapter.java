package com.gazbert.bxbot.strategies;

import com.gazbert.bxbot.strategy.api.StrategyConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;

public class IntelligentLimitAdapter {

    private static final Logger LOG = LogManager.getLogger();
    /** The decimal format for the logs. */
    private static final DecimalFormat decimalFormat = new DecimalFormat("#.########");
    private static final BigDecimal oneHundred = new BigDecimal(100);

    /**
     * The minimum % gain to reach according to the recorded minimum before placing a BUY oder. This was loaded from the strategy
     * entry in the {project-root}/config/strategies.yaml config file.
     */
    private final BigDecimal configuredPercentageGainNeededToPlaceBuyOrder;
    private final BigDecimal configuredSellStopLimitPercentageBelowBreakEven;
    private final BigDecimal configuredSellStopLimitPercentageAboveBreakEven;
    private final BigDecimal configuredSellStopLimitPercentageMinimumAboveBreakEven;
    private final BigDecimal configuredIntelligentLimitsPercentageScaleFactor;
    private final int configuredLookback;
    private final int configuredNeededUpMovement;

    private BigDecimal overallStrategyGain;
    private int amountOfPositiveTrades;
    private BigDecimal overallPositiveGains;
    private int amountOfNegativeTrades;
    private BigDecimal overallNegativeLosses;

    public IntelligentLimitAdapter(StrategyConfig config) {
        configuredPercentageGainNeededToPlaceBuyOrder = StrategyConfigParser.readPercentageConfigValue(config, "initial-percentage-gain-needed-to-place-buy-order");
        configuredSellStopLimitPercentageBelowBreakEven = StrategyConfigParser.readPercentageConfigValue(config,"sell-stop-limit-percentage-below-break-even");
        configuredSellStopLimitPercentageAboveBreakEven = StrategyConfigParser.readPercentageConfigValue(config,"sell-stop-limit-percentage-above-break-even");
        configuredSellStopLimitPercentageMinimumAboveBreakEven = StrategyConfigParser.readPercentageConfigValue(config,"sell-stop-limit-percentage-minimum-above-break-even");
        configuredIntelligentLimitsPercentageScaleFactor = StrategyConfigParser.readPercentageConfigValue(config, "intelligent-limits-percentage-scale-factor");
        configuredLookback = StrategyConfigParser.readInteger(config, "lowest-price-lookback-count");
        configuredNeededUpMovement = StrategyConfigParser.readInteger(config, "times-above-lowest-price-needed");
        if (configuredNeededUpMovement > configuredLookback) {
            throw new IllegalArgumentException("The amount for checking if the prices moved up must be lower or equal to the configured overall lookback");
        }

        overallStrategyGain = BigDecimal.ZERO;
        overallPositiveGains = BigDecimal.ZERO;
        overallNegativeLosses = BigDecimal.ZERO;
        amountOfPositiveTrades = 0;
        amountOfNegativeTrades = 0;
        printCurrentStatistics();
    }

    public void addNewExecutedSellOrder(OrderState currentSellOrder, BigDecimal totalGain, BigDecimal breakEven) {
        BigDecimal oldOverallStrategyGain = overallStrategyGain;
        overallStrategyGain = overallStrategyGain.add(totalGain);

        if (totalGain.compareTo(BigDecimal.ZERO)>0) {
            LOG.info(() -> "New postive sell order  acquired. Increased the overall strategy gain from '"+decimalFormat.format(oldOverallStrategyGain) + "' to '" + decimalFormat.format(overallStrategyGain)+ "'." );
            amountOfPositiveTrades++;
            overallPositiveGains = overallPositiveGains.add(totalGain);
        } else {
            LOG.info(() -> "New negative sell order  acquired. Reduced the overall strategy gain from '"+decimalFormat.format(oldOverallStrategyGain) + "' to '" + decimalFormat.format(overallStrategyGain)+ "'." );
            amountOfNegativeTrades++;
            overallNegativeLosses = overallNegativeLosses.add(totalGain);
        }

        printCurrentStatistics();
    }

    public BigDecimal getCurrentPercentageGainNeededForBuy() {
        return scaleDown(configuredPercentageGainNeededToPlaceBuyOrder);
    }

    public BigDecimal getCurrentSellStopLimitPercentageAboveBreakEven() {
        return scaleUp(configuredSellStopLimitPercentageAboveBreakEven);
    }

    public BigDecimal getCurrentSellStopLimitPercentageBelowBreakEven() {
        return scaleUp(configuredSellStopLimitPercentageBelowBreakEven);
    }

    public BigDecimal getCurrentSellStopLimitPercentageMinimumAboveBreakEven() {
        return scaleUp(configuredSellStopLimitPercentageMinimumAboveBreakEven);
    }

    private BigDecimal scaleUp(BigDecimal initialPercentage) {
        BigDecimal result = initialPercentage; // 0.05 == 5%
        BigDecimal scaleFactor = configuredIntelligentLimitsPercentageScaleFactor; // 0.1 == 10%
        if(scaleFactor.compareTo(BigDecimal.ZERO) == 0){
            return initialPercentage;
        }
        BigDecimal postiveMultiplicant = BigDecimal.ONE.add(BigDecimal.ONE.multiply(scaleFactor)); // 1 + (1*0.1) = 1+0.1 = 1.1
        int currentSellLossRatio = amountOfPositiveTrades - amountOfNegativeTrades; //  Neg: 2-6 = -4, Pos: 6-2=4
        BigDecimal currentScalingFactor = postiveMultiplicant.pow(currentSellLossRatio, new MathContext(8, RoundingMode.HALF_UP)); // Neg: 1.1^(-4) = 0.6830, Pos: 1.1^4 = 1.4641
        return result.multiply(currentScalingFactor); // Neg: 0.05*0.6830 = 0.03415, Pos: 0.05 * 1.4641 = 0.0732
    }

    private BigDecimal scaleDown(BigDecimal initialPercentage) {
        BigDecimal result = initialPercentage; // 0.05 == 5%
        BigDecimal scaleFactor = configuredIntelligentLimitsPercentageScaleFactor; // 0.1 == 10%
        if(scaleFactor.compareTo(BigDecimal.ZERO) == 0){
            return initialPercentage;
        }
        BigDecimal negativeMultiplicant = BigDecimal.ONE.subtract(BigDecimal.ONE.multiply(scaleFactor)); // 1 - (1*0.1) = 1-0.1 = 0.9
        int currentSellLossRatio = amountOfPositiveTrades - amountOfNegativeTrades; //  Neg: 2-6 = -4, Pos: 6-2=4
        BigDecimal currentScalingFactor = negativeMultiplicant.pow(currentSellLossRatio, new MathContext(8, RoundingMode.HALF_UP)); // Neg: 0.9^(-4) = 1.5241, Pos: 0.9^4 = 0.6561
        return result.multiply(currentScalingFactor); // Neg: 0.05*1.5241 = 0.0762, Pos: 0.05 * 0.6561 = 0.0328
    }

    public String calculateCurrentStatistics() {
        return "The current statistics are:\n"
                + "######### LIMIT ADAPTION STATISTICS #########\n"
                + "* Overall strategy gain: " +decimalFormat.format(overallStrategyGain) + "\n"
                + "* Positive trades: " + amountOfPositiveTrades + "\n"
                + "* Sum of positive wins: " +decimalFormat.format(overallPositiveGains) + "\n"
                + "* Negative trades: " + amountOfNegativeTrades + "\n"
                + "* Sum of negative losses: " +decimalFormat.format(overallNegativeLosses) + "\n"
                + "---------------------------------------------\n"
                + "* configured scale factor: " + decimalFormat.format(configuredIntelligentLimitsPercentageScaleFactor.multiply(new BigDecimal(100))) + "%\n"
                + "* configured lowest price lookback count: " + configuredLookback + "\n"
                + "* configured times needed above lowest price: " +configuredNeededUpMovement+ "\n"
                + "---------------------------------------------\n"
                + "* current percentage gain needed for buy: " +decimalFormat.format(getCurrentPercentageGainNeededForBuy().multiply(new BigDecimal(100))) + "%\n"
                + "* current sell stop limit percentage above break even: " +decimalFormat.format(getCurrentSellStopLimitPercentageAboveBreakEven().multiply(new BigDecimal(100))) + "%\n"
                + "* current sell stop limit percentage minimum above break even: " +decimalFormat.format(getCurrentSellStopLimitPercentageMinimumAboveBreakEven().multiply(new BigDecimal(100))) + "%\n"
                + "* current sell stop limit percentage below break even:: " +decimalFormat.format(getCurrentSellStopLimitPercentageBelowBreakEven().multiply(new BigDecimal(100))) + "%\n"
                + "---------------------------------------------\n"
                + "* initial percentage gain needed for buy: " +decimalFormat.format(configuredPercentageGainNeededToPlaceBuyOrder.multiply(new BigDecimal(100))) + "%\n"
                + "* initial sell stop limit percentage above break even: " +decimalFormat.format(configuredSellStopLimitPercentageAboveBreakEven.multiply(new BigDecimal(100))) + "%\n"
                + "* initial sell stop limit percentage minimum above break even: " +decimalFormat.format(configuredSellStopLimitPercentageMinimumAboveBreakEven.multiply(new BigDecimal(100))) + "%\n"
                + "* initial sell stop limit percentage below break even:: " +decimalFormat.format(configuredSellStopLimitPercentageBelowBreakEven.multiply(new BigDecimal(100))) + "%\n"
                + "#############################################";
    }
    public void printCurrentStatistics() {
        LOG.info(this::calculateCurrentStatistics);
    }

    public int getCurrentLowestPriceLookbackCount() {
        return configuredLookback;
    }

    public int getCurrentTimesAboveLowestPriceNeeded() {
        return configuredNeededUpMovement;
    }

    public BigDecimal getOverallStrategyGain() {
        return overallStrategyGain;
    }

    public int getAmountOfPositiveTrades() {
        return amountOfPositiveTrades;
    }

    public int getAmountOfNegativeTrades() {
        return amountOfNegativeTrades;
    }

    public int getAmountOfTrades() {
        return amountOfPositiveTrades + amountOfNegativeTrades;
    }

    public BigDecimal getOverallPositiveGain() {
        return overallPositiveGains;
    }

    public BigDecimal getOverallNegativeLosses() {
        return overallNegativeLosses;
    }
}
