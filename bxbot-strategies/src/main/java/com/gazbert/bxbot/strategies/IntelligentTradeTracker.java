package com.gazbert.bxbot.strategies;

import com.gazbert.bxbot.strategies.helper.IntelligentStateTracker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
import java.text.DecimalFormat;

public class IntelligentTradeTracker implements IntelligentStateTracker.OnTradeSuccessfullyClosedListener {

    private static final Logger LOG = LogManager.getLogger();
    /**
     * The decimal format for the logs.
     */
    protected static final DecimalFormat decimalFormat = new DecimalFormat("#.########");

    private BigDecimal overallStrategyGain;
    private int amountOfPositiveTrades;
    private BigDecimal overallPositiveGains;
    private int amountOfNegativeTrades;
    private BigDecimal overallNegativeLosses;

    private int amountOfNeutralTrades;

    public IntelligentTradeTracker() {
        overallStrategyGain = BigDecimal.ZERO;
        overallPositiveGains = BigDecimal.ZERO;
        overallNegativeLosses = BigDecimal.ZERO;
        amountOfPositiveTrades = 0;
        amountOfNegativeTrades = 0;
        amountOfNeutralTrades = 0;
    }

    @Override
    public void onTradeCloseSuccess(BigDecimal profit) {

        BigDecimal oldOverallStrategyGain = overallStrategyGain;
        overallStrategyGain = overallStrategyGain.add(profit);

        if (profit.compareTo(BigDecimal.ZERO) > 0) {
            LOG.info(() -> "New postive sell order  acquired. Increased the overall strategy gain from '" + decimalFormat.format(oldOverallStrategyGain) + "' to '" + decimalFormat.format(overallStrategyGain) + "'.");
            amountOfPositiveTrades++;
            overallPositiveGains = overallPositiveGains.add(profit);
        } else {
            if (profit.compareTo(BigDecimal.ZERO) == 0) {
                LOG.info(() -> "New neutral sell order acquired. No changes in the overall strategy gain: '" + decimalFormat.format(oldOverallStrategyGain) + "'.");
                amountOfNeutralTrades++;
            } else {
                LOG.info(() -> "New negative sell order  acquired. Reduced the overall strategy gain from '" + decimalFormat.format(oldOverallStrategyGain) + "' to '" + decimalFormat.format(overallStrategyGain) + "'.");
                amountOfNegativeTrades++;
                overallNegativeLosses = overallNegativeLosses.add(profit);
            }
        }

        logStatistics();
    }

    protected String calculateCurrentStatistics() {
        return    "\n######### TRADE STATISTICS #########\n"
                + "* Overall strategy gain: " + decimalFormat.format(getOverallStrategyGain()) + "\n"
                + "* Positive trades: " + getAmountOfPositiveTrades() + "\n"
                + "* Sum of positive wins: " + decimalFormat.format(getOverallPositiveGain()) + "\n"
                + "* Negative trades: " + getAmountOfNegativeTrades() + "\n"
                + "* Sum of negative losses: " + decimalFormat.format(getOverallNegativeLosses()) + "\n"
                + "* Neutral trades: " + getAmountOfNeutralTrades() + "\n"
                + "####################################";
    }

    @Override
    public void logStatistics() {
        LOG.info(this::calculateCurrentStatistics);
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

    public int getAmountOfNeutralTrades() {
        return amountOfNeutralTrades;
    }

    public int getAmountOfTrades() {
        return amountOfPositiveTrades + amountOfNegativeTrades + amountOfNeutralTrades;
    }

    public BigDecimal getOverallPositiveGain() {
        return overallPositiveGains;
    }

    public BigDecimal getOverallNegativeLosses() {
        return overallNegativeLosses;
    }
}
