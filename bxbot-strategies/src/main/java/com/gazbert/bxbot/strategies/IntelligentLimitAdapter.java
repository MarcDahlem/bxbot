package com.gazbert.bxbot.strategies;

import com.gazbert.bxbot.strategy.api.StrategyConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
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
    private BigDecimal configuredPercentageGainNeededToPlaceBuyOrder;
    private final BigDecimal configuredSellStopLimitPercentageBelowBreakEven;
    private final BigDecimal configuredSellStopLimitPercentageAboveBreakEven;
    private final BigDecimal configuredSellStopLimitPercentageMinimumAboveBreakEven;
    private final BigDecimal configuredIntelligentLimitsPercentageScaleFactor;

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
        BigDecimal result = initialPercentage;
        int currentSellLossRatio = amountOfPositiveTrades - amountOfNegativeTrades;
        if (currentSellLossRatio == 0) return result;
        if (currentSellLossRatio >0) {
            while (currentSellLossRatio >0) {
                result = result.add(result.multiply(configuredIntelligentLimitsPercentageScaleFactor));
                currentSellLossRatio--;
            }
        } else {
            while (currentSellLossRatio <0) {
                result = result.subtract(result.multiply(configuredIntelligentLimitsPercentageScaleFactor));
                currentSellLossRatio++;
            }
        }
        return result;
    }

    private BigDecimal scaleDown(BigDecimal initialPercentage) {
        BigDecimal result = initialPercentage;
        int currentSellLossRatio = amountOfPositiveTrades - amountOfNegativeTrades;
        if (currentSellLossRatio == 0) return result;
        if (currentSellLossRatio >0) {
            while (currentSellLossRatio >0) {
                result = result.subtract(result.multiply(configuredIntelligentLimitsPercentageScaleFactor));
                currentSellLossRatio--;
            }
        } else {
            while (currentSellLossRatio <0) {
                result = result.add(result.multiply(configuredIntelligentLimitsPercentageScaleFactor));
                currentSellLossRatio++;
            }
        }
        return result;
    }

    public void printCurrentStatistics() {
        LOG.info(() -> "The current statistics are:\n"
                + "######### LIMIT ADAPTION STATISTICS #########\n"
                + "* Overall strategy gain: " +decimalFormat.format(overallStrategyGain) + "\n"
                + "* Positive trades: " + amountOfPositiveTrades + "\n"
                + "* Sum of positive wins: " +decimalFormat.format(overallPositiveGains) + "\n"
                + "* Negative trades: " + amountOfNegativeTrades + "\n"
                + "* Sum of negative losses: " +decimalFormat.format(overallNegativeLosses) + "\n"
                + "---------------------------------------------\n"
                + "* configured scale factor: " + decimalFormat.format(configuredIntelligentLimitsPercentageScaleFactor.multiply(new BigDecimal(100))) + "%\n"
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
                + "#############################################"
        );
    }
}
