package com.gazbert.bxbot.strategies;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
import java.text.DecimalFormat;

public class IntelligentLimitAdapter {

    private static final Logger LOG = LogManager.getLogger();
    /** The decimal format for the logs. */
    private static final DecimalFormat decimalFormat = new DecimalFormat("#.########");
    private static final BigDecimal oneHundred = new BigDecimal(100);

    private final BigDecimal initialPercentageGainNeededToPlaceBuyOrder;
    private final BigDecimal initialSellStopLimitPercentageBelowBreakEven;
    private final BigDecimal initialSellStopLimitPercentageAboveBreakEven;
    private final BigDecimal initialScalePercentage;

    private BigDecimal overallStrategyGain;
    private int amountOfPositiveTrades;
    private BigDecimal overallPositiveGains;
    private int amountOfNegativeTrades;
    private BigDecimal overallNegativeLosses;

    public IntelligentLimitAdapter(BigDecimal initialPercentageGainNeededToPlaceBuyOrder,
                            BigDecimal initialSellStopLimitPercentageBelowBreakEven,
                            BigDecimal initialSellStopLimitPercentageAboveBreakEven,
                            BigDecimal initialScalePercentage) {

        this.initialPercentageGainNeededToPlaceBuyOrder = initialPercentageGainNeededToPlaceBuyOrder;
        this.initialSellStopLimitPercentageBelowBreakEven = initialSellStopLimitPercentageBelowBreakEven;
        this.initialSellStopLimitPercentageAboveBreakEven = initialSellStopLimitPercentageAboveBreakEven;
        this.initialScalePercentage = initialScalePercentage;
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

    public void printCurrentStatistics() {
        LOG.info(() -> "The current statistics are:\n"
                + "* Overall strategy gain: " +decimalFormat.format(overallStrategyGain) + "\n"
                + "* Positive trades: " + amountOfPositiveTrades + "\n"
                + "* Sum of positive wins: " +decimalFormat.format(overallPositiveGains) + "\n"
                + "* Negative trades: " + amountOfNegativeTrades + "\n"
                + "* Sum of negative losses: " +decimalFormat.format(overallNegativeLosses) + "\n"
                + "* current percentage gain needed for buy: " +decimalFormat.format(getCurrentPercentageGainNeededForBuy().multiply(new BigDecimal(100))) + "%\n"
                + "* current sell stop limit percentage above break even: " +decimalFormat.format(getCurrentSellStopLimitPercentageAboveBreakEven().multiply(new BigDecimal(100))) + "%\n"
                + "* current sell stop limit percentage below break even:: " +decimalFormat.format(getCurrentSellStopLimitPercentageBelowBreakEven().multiply(new BigDecimal(100))) + "%\n"
                + "* initial percentage gain needed for buy: " +decimalFormat.format(initialPercentageGainNeededToPlaceBuyOrder.multiply(new BigDecimal(100))) + "%\n"
                + "* initial sell stop limit percentage above break even: " +decimalFormat.format(initialSellStopLimitPercentageAboveBreakEven.multiply(new BigDecimal(100))) + "%\n"
                + "* initial sell stop limit percentage below break even:: " +decimalFormat.format(initialSellStopLimitPercentageBelowBreakEven.multiply(new BigDecimal(100))) + "%"
        );
    }

    public BigDecimal getCurrentPercentageGainNeededForBuy() {
        return scaleDown(initialPercentageGainNeededToPlaceBuyOrder);
    }

    public BigDecimal getCurrentSellStopLimitPercentageAboveBreakEven() {
        return scaleUp(initialSellStopLimitPercentageAboveBreakEven);
    }

    public BigDecimal getCurrentSellStopLimitPercentageBelowBreakEven() {
        return scaleUp(initialSellStopLimitPercentageBelowBreakEven);
    }

    private BigDecimal scaleUp(BigDecimal initialPercentage) {
        BigDecimal result = initialPercentage;
        int currentSellLossRatio = amountOfPositiveTrades - amountOfNegativeTrades;
        if (currentSellLossRatio == 0) return result;
        if (currentSellLossRatio >0) {
            while (currentSellLossRatio >0) {
                result = result.add(result.multiply(initialScalePercentage));
                currentSellLossRatio--;
            }
        } else {
            while (currentSellLossRatio <0) {
                result = result.subtract(result.multiply(initialScalePercentage));
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
                result = result.subtract(result.multiply(initialScalePercentage));
                currentSellLossRatio--;
            }
        } else {
            while (currentSellLossRatio <0) {
                result = result.add(result.multiply(initialScalePercentage));
                currentSellLossRatio++;
            }
        }
        return result;
    }
}
