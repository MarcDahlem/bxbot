package com.gazbert.bxbot.strategies;

import com.gazbert.bxbot.strategies.helper.IntelligentStateTracker;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;

public class IntelligentTrailingStopConfigParams extends IntelligentTradeTracker {

    private static final Logger LOG = LogManager.getLogger();

    /**
     * The minimum % gain to reach according to the recorded minimum before placing a BUY oder. This was loaded from the strategy
     * entry in the {project-root}/config/strategies.yaml config file.
     */
    private final BigDecimal configuredPercentageGainNeededToPlaceBuyOrder;
    private final BigDecimal configuredSellStopLimitPercentageBelowBreakEven;
    private final BigDecimal configuredSellStopLimitPercentageAboveBreakEven;
    private final BigDecimal configuredSellStopLimitPercentageMinimumAboveBreakEven;
    private final int configuredLookback;
    private final int configuredNeededUpMovement;

    public IntelligentTrailingStopConfigParams(StrategyConfig config) {
        super();
        configuredPercentageGainNeededToPlaceBuyOrder = StrategyConfigParser.readPercentageConfigValue(config, "initial-percentage-gain-needed-to-place-buy-order");
        configuredSellStopLimitPercentageBelowBreakEven = StrategyConfigParser.readPercentageConfigValue(config, "sell-stop-limit-percentage-below-break-even");
        configuredSellStopLimitPercentageAboveBreakEven = StrategyConfigParser.readPercentageConfigValue(config, "sell-stop-limit-percentage-above-break-even");
        configuredSellStopLimitPercentageMinimumAboveBreakEven = StrategyConfigParser.readPercentageConfigValue(config, "sell-stop-limit-percentage-minimum-above-break-even");
        configuredLookback = StrategyConfigParser.readInteger(config, "lowest-price-lookback-count");
        configuredNeededUpMovement = StrategyConfigParser.readInteger(config, "times-above-lowest-price-needed");
        if (configuredNeededUpMovement > configuredLookback) {
            throw new IllegalArgumentException("The amount for checking if the prices moved up must be lower or equal to the configured overall lookback");
        }

        logStatistics();
    }

    public BigDecimal getCurrentPercentageGainNeededForBuy() {
        return configuredPercentageGainNeededToPlaceBuyOrder;
    }

    public BigDecimal getCurrentSellStopLimitPercentageAboveBreakEven() {
        return configuredSellStopLimitPercentageAboveBreakEven;
    }

    public BigDecimal getCurrentSellStopLimitPercentageBelowBreakEven() {
        return configuredSellStopLimitPercentageBelowBreakEven;
    }

    public BigDecimal getCurrentSellStopLimitPercentageMinimumAboveBreakEven() {
        return configuredSellStopLimitPercentageMinimumAboveBreakEven;
    }

    public String calculateCurrentStatistics() {
        return super.calculateCurrentStatistics()
                + "######### LIMIT ADAPTION STATISTICS #########\n"
                + "---------------------------------------------\n"
                + "* configured lowest price lookback count: " + configuredLookback + "\n"
                + "* configured times needed above lowest price: " + configuredNeededUpMovement + "\n"
                + "---------------------------------------------\n"
                + "* configured percentage gain needed for buy: " + decimalFormat.format(configuredPercentageGainNeededToPlaceBuyOrder.multiply(new BigDecimal(100))) + "%\n"
                + "* configured sell stop limit percentage above break even: " + decimalFormat.format(configuredSellStopLimitPercentageAboveBreakEven.multiply(new BigDecimal(100))) + "%\n"
                + "* configured sell stop limit percentage minimum above break even: " + decimalFormat.format(configuredSellStopLimitPercentageMinimumAboveBreakEven.multiply(new BigDecimal(100))) + "%\n"
                + "* configured sell stop limit percentage below break even:: " + decimalFormat.format(configuredSellStopLimitPercentageBelowBreakEven.multiply(new BigDecimal(100))) + "%\n"
                + "#############################################";
    }

    public int getCurrentLowestPriceLookbackCount() {
        return configuredLookback;
    }

    public int getCurrentTimesAboveLowestPriceNeeded() {
        return configuredNeededUpMovement;
    }

}
