package com.gazbert.bxbot.strategies;

import com.gazbert.bxbot.strategies.helper.IntelligentStateTracker;
import com.gazbert.bxbot.trading.api.Market;
import com.gazbert.bxbot.trading.api.util.ta4j.MarketEnterType;
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
    private BigDecimal overallStrategyLongGain;
    private BigDecimal overallStrategyShortGain;

    private int amountOfPositiveTrades;
    private int amountOfPositiveLongTrades;
    private int amountOfPositiveShortTrades;

    private BigDecimal overallPositiveGains;
    private BigDecimal overallPositiveLongGains;
    private BigDecimal overallPositiveShortGains;

    private int amountOfNegativeTrades;
    private int amountOfNegativeLongTrades;
    private int amountOfNegativeShortTrades;

    private BigDecimal overallNegativeLosses;
    private BigDecimal overallNegativeLongLosses;
    private BigDecimal overallNegativeShortLosses;

    private int amountOfNeutralTrades;
    private int amountOfNeutralLongTrades;
    private int amountOfNeutralShortTrades;

    public IntelligentTradeTracker() {
        overallStrategyGain = BigDecimal.ZERO;
        overallStrategyLongGain = BigDecimal.ZERO;
        overallStrategyShortGain = BigDecimal.ZERO;

        overallPositiveGains = BigDecimal.ZERO;
        overallPositiveLongGains = BigDecimal.ZERO;
        overallPositiveShortGains = BigDecimal.ZERO;

        overallNegativeLosses = BigDecimal.ZERO;
        overallNegativeLongLosses = BigDecimal.ZERO;
        overallNegativeShortLosses = BigDecimal.ZERO;

        amountOfPositiveTrades = 0;
        amountOfPositiveLongTrades = 0;
        amountOfPositiveShortTrades = 0;

        amountOfNegativeTrades = 0;
        amountOfNegativeLongTrades = 0;
        amountOfNegativeShortTrades = 0;

        amountOfNeutralTrades = 0;
        amountOfNeutralLongTrades = 0;
        amountOfNeutralShortTrades = 0;
    }

    @Override
    public void onTradeCloseSuccess(BigDecimal profit, MarketEnterType marketEnterType) {

        BigDecimal oldOverallStrategyGain = overallStrategyGain;
        overallStrategyGain = overallStrategyGain.add(profit);
        if (marketEnterType == MarketEnterType.LONG_POSITION) {
            overallStrategyLongGain = overallStrategyLongGain.add(profit);
        } else {
            overallStrategyShortGain = overallStrategyShortGain.add(profit);
        }

        if (profit.compareTo(BigDecimal.ZERO) > 0) {
            LOG.info(() -> "New postive EXIT order  acquired. Increased the overall strategy gain from '" + decimalFormat.format(oldOverallStrategyGain) + "' to '" + decimalFormat.format(overallStrategyGain) + "'.");
            amountOfPositiveTrades++;
            overallPositiveGains = overallPositiveGains.add(profit);
            if (marketEnterType == MarketEnterType.LONG_POSITION) {
                amountOfPositiveLongTrades++;
                overallPositiveLongGains =overallPositiveLongGains.add(profit);
            } else {
                amountOfPositiveShortTrades++;
                overallPositiveShortGains = overallPositiveShortGains.add(profit);
            }

        } else {
            if (profit.compareTo(BigDecimal.ZERO) == 0) {
                LOG.info(() -> "New neutral EXIT order acquired. No changes in the overall strategy gain: '" + decimalFormat.format(oldOverallStrategyGain) + "'.");
                amountOfNeutralTrades++;
                if (marketEnterType == MarketEnterType.LONG_POSITION) {
                    amountOfNeutralLongTrades++;
                } else {
                    amountOfNeutralShortTrades++;
                }
            } else {
                LOG.info(() -> "New negative EXIT order acquired. Reduced the overall strategy gain from '" + decimalFormat.format(oldOverallStrategyGain) + "' to '" + decimalFormat.format(overallStrategyGain) + "'.");
                amountOfNegativeTrades++;
                overallNegativeLosses = overallNegativeLosses.add(profit);
                if (marketEnterType == MarketEnterType.LONG_POSITION) {
                    amountOfNegativeLongTrades++;
                    overallNegativeLongLosses = overallNegativeLongLosses.add(profit);
                } else {
                    amountOfNegativeShortTrades++;
                    overallNegativeShortLosses = overallNegativeShortLosses.add(profit);
                }
            }
        }

        logStatistics();
    }

    protected String calculateCurrentStatistics() {
        return    "\n######### TRADE STATISTICS #########\n"
                + "* Overall strategy gain: " + decimalFormat.format(getOverallStrategyGain()) + "\n"
                + "* Overall strategy gain (long only): " + decimalFormat.format(overallStrategyLongGain) + "\n"
                + "* Overall strategy gain (short only): " + decimalFormat.format(overallStrategyShortGain) + "\n"
                + "* Positive trades: " + getAmountOfPositiveTrades() + "\n"
                + "* Positive trades (long only): " + amountOfPositiveLongTrades + "\n"
                + "* Positive trades (short only): " + amountOfPositiveShortTrades + "\n"
                + "* Sum of positive wins: " + decimalFormat.format(getOverallPositiveGain()) + "\n"
                + "* Sum of positive wins (long only): " + decimalFormat.format(overallPositiveLongGains) + "\n"
                + "* Sum of positive wins (short only): " + decimalFormat.format(overallPositiveShortGains) + "\n"
                + "* Negative trades: " + getAmountOfNegativeTrades() + "\n"
                + "* Negative trades (long only): " + amountOfNegativeLongTrades + "\n"
                + "* Negative trades (short only): " + amountOfNegativeShortTrades + "\n"
                + "* Sum of negative losses: " + decimalFormat.format(getOverallNegativeLosses()) + "\n"
                + "* Sum of negative losses (long only): " + decimalFormat.format(overallNegativeLongLosses) + "\n"
                + "* Sum of negative losses (short only): " + decimalFormat.format(overallNegativeShortLosses) + "\n"
                + "* Neutral trades: " + getAmountOfNeutralTrades() + "\n"
                + "* Neutral trades (long only): " + amountOfNeutralLongTrades + "\n"
                + "* Neutral trades (short only): " + amountOfNeutralShortTrades + "\n"
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
