package com.gazbert.bxbot.strategies;

import com.gazbert.bxbot.strategy.api.StrategyConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

public class StrategyConfigParser {
    private static final Logger LOG = LogManager.getLogger();
    /** The decimal format for the logs. */
    private static final DecimalFormat decimalFormat = new DecimalFormat("#.########");
    public static BigDecimal readPercentageConfigValue(StrategyConfig config, String configKeyToPercentageValue) {
        final String initialPercentageValueAsString =
                config.getConfigItem(configKeyToPercentageValue);
        if (initialPercentageValueAsString == null) {
            throw new IllegalArgumentException(
                    "Mandatory <" + configKeyToPercentageValue + "> misses a value in strategy.xml config.");
        }
        LOG.info(() -> "<" + configKeyToPercentageValue + "> from config is: " + initialPercentageValueAsString);

        BigDecimal initialPercentageValue = new BigDecimal(initialPercentageValueAsString);

        BigDecimal initialPercentageValueInDecimal = initialPercentageValue.divide(new BigDecimal(100), 8, RoundingMode.HALF_UP);
        return initialPercentageValueInDecimal;
    }

    public static boolean readBoolean(StrategyConfig config, String key, boolean defaultValue) {
        final String valueAsString = config.getConfigItem(key);
        if (valueAsString == null || valueAsString.isEmpty()) {
            LOG.info(() -> "Configuration value of <" + key + "> is not available in the strategy.xml config. Use the default value '" + defaultValue + "' instead.");
            return defaultValue;
        } else {
            Boolean result = Boolean.valueOf(valueAsString);
            LOG.info(() -> "Successfully read the configuration value of <" + key + "> from the strategy.xml as '" + result + "'");
            return result;
        }
    }

    public static BigDecimal readAmount(StrategyConfig config, String key) {
        final String numberValueAsString = config.getConfigItem(key);
        if (numberValueAsString == null) {
            throw new IllegalArgumentException(
                    "Mandatory <" + key + "> misses a value in strategy.xml config.");
        }
        LOG.info(() -> "<" + key + "> from config is: " + numberValueAsString);

        BigDecimal numberValue = new BigDecimal(numberValueAsString);
        return numberValue;
    }
}
