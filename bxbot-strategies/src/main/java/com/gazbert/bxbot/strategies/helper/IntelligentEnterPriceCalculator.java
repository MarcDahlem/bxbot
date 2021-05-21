package com.gazbert.bxbot.strategies.helper;

import com.gazbert.bxbot.strategies.StrategyConfigParser;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.Market;
import com.gazbert.bxbot.trading.api.TradingApiException;

import com.gazbert.bxbot.trading.api.util.ta4j.MarketEnterType;
import java.math.BigDecimal;

public class IntelligentEnterPriceCalculator extends AbstractEnterPriceCalculator {

    /**
     * The % of the the available counter currency balance to be used for entry orders. This was loaded from the strategy
     * entry in the {project-root}/config/strategies.yaml config file.
     */
    private final BigDecimal configuredPercentageOfCounterCurrencyBalanceToUse;
    /**
     * The emergency stop that should always be left over in the counter currency balance.
     * This is an entry in the {project-root}/config/strategies.yaml config file.
     * It should be the same as the value of 'emergencyStopBalance' in the {project-root}/config/engine.yaml
     */
    private final BigDecimal configuredEmergencyStop;

    public IntelligentEnterPriceCalculator(Market market, IntelligentPriceTracker priceTracker, StrategyConfig config) {
        super(market, priceTracker);

        configuredPercentageOfCounterCurrencyBalanceToUse = StrategyConfigParser.readPercentageConfigValue(config, "percentage-of-counter-currency-balance-to-use");
        configuredEmergencyStop = StrategyConfigParser.readAmount(config, "configured-emergency-stop-balance");
    }

    @Override
    protected BigDecimal getBalanceToUseForEnterOrder(MarketEnterType type) throws ExchangeNetworkException, TradingApiException, StrategyException {

        final BigDecimal currentBalance = priceTracker.getAvailableCounterCurrencyBalance();

        BigDecimal balanceAvailableForTrading = currentBalance.subtract(configuredEmergencyStop);
        if (balanceAvailableForTrading.compareTo(BigDecimal.ZERO) <= 0) {
            String errorMsg = "No balance available for trading. When substracting the emergency stop, the remaining balance is " + priceTracker.formatWithCounterCurrency(balanceAvailableForTrading);
            LOG.error(() -> market.getName() + errorMsg);
            throw new StrategyException(errorMsg);
        }
        LOG.info(() -> market.getName() + "Balance available after being reduced by the emergency stop: " + priceTracker.formatWithCounterCurrency(balanceAvailableForTrading));
        BigDecimal balanceToUseForEntryOrder = balanceAvailableForTrading.multiply(configuredPercentageOfCounterCurrencyBalanceToUse);
        LOG.info(() -> market.getName() + "Balance to be used for trading, taking into consideration the configured trading percentage of "
                + DECIMAL_FORMAT.format(configuredPercentageOfCounterCurrencyBalanceToUse) + ": " + priceTracker.formatWithCounterCurrency(balanceToUseForEntryOrder));
        return balanceToUseForEntryOrder;
    }
}
