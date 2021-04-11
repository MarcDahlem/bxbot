/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Gareth Jon Lynch
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.gazbert.bxbot.strategies;

import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.strategy.api.TradingStrategy;
import com.gazbert.bxbot.trading.api.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.UUID;

@Component("intelligentTrailingStopStrategy") // used to load the strategy using Spring bean injection
public class IntelligentTrailingStopStrategy implements TradingStrategy {

  private static final Logger LOG = LogManager.getLogger();

  /** The decimal format for the logs. */
  private static final String DECIMAL_FORMAT = "#.########";

  /** Reference to the main Trading API. */
  private TradingApi tradingApi;

  /** The market this strategy is trading on. */
  private Market market;

  private IntelligentStrategyState strategyState;

  /**
   * The minimum % gain to reach according to the recorded minimum before placing a BUY oder. This was loaded from the strategy
   * entry in the {project-root}/config/strategies.yaml config file.
   */
  private BigDecimal configuredInitialPercentageGainNeededToPlaceBuyOrder;
  /**
   * The % of the the available counter currency balance to be used for buy orders. This was loaded from the strategy
   * entry in the {project-root}/config/strategies.yaml config file.
   */
  private BigDecimal configuredPercentageOfCounterCurrencyBalanceToUse;
  /**
   * The emergency stop that should always be left over in the counter currency balance.
   * This is an entry in the {project-root}/config/strategies.yaml config file.
   * It should be the same as the value of 'emergencyStopBalance' in the {project-root}/config/engine.yaml
   */
  private BigDecimal configuredEmergencyStop;


  /* market data downloaded and stored during the engine lifetime */
  private BigDecimal currentMarketPrice;
  private BigDecimal lowestPrice;

  /* used to store the latest executed order */
  private OrderState currentOrder;


  /**
   * Initialises the Trading Strategy. Called once by the Trading Engine when the bot starts up;
   * it's a bit like a servlet init() method.
   *
   * @param tradingApi the Trading API. Use this to make trades and stuff.
   * @param market the market for this strategy. This is the market the strategy is currently
   *     running on - you wire this up in the markets.yaml and strategies.yaml files.
   * @param config configuration for the strategy. Contains any (optional) config you set up in the
   *     strategies.yaml file.
   */
  @Override
  public void init(TradingApi tradingApi, Market market, StrategyConfig config) {
    LOG.info(() -> "Initialising Trading Strategy...");
    this.tradingApi = tradingApi;
    this.market = market;
    getConfigForStrategy(config);
    LOG.info(() -> "Trading Strategy initialised successfully!");
  }

  /**
   * This is the main execution method of the Trading Strategy. It is where your algorithm lives.
   *
   * <p>It is called by the Trading Engine during each trade cycle, e.g. every 60s. The trade cycle
   * is configured in the {project-root}/config/engine.yaml file.
   *
   * @throws StrategyException if something unexpected occurs. This tells the Trading Engine to
   *     shutdown the bot immediately to help prevent unexpected losses.
   */
  @Override
  public void execute() throws StrategyException {

    try {
      if (strategyState == null) {
        LOG.info(() -> market.getName() + " First time that the strategy has been called - get the initial strategy state.");
        computeInitialStrategyState();
        LOG.info(() -> market.getName() + " Initial strategy state computed: " + this.strategyState);
      }

      updateMarketPrices();
      switch(strategyState) {
        case NEED_BUY:
          executeBuyPhase();
          break;
        case NEED_SELL:
          break;
        case WAIT_FOR_BUY:
          break;
        default:
          throw new StrategyException("Unknown strategy state encounted: " + strategyState);
      }
    } catch (TradingApiException | ExchangeNetworkException e) {
      // We are just going to re-throw as StrategyException for engine to deal with - it will
      // shutdown the bot.
      LOG.error(
          market.getName()
              + " Failed to perform the strategy because Exchange threw TradingApiException or ExchangeNetworkexception. "
              + "Telling Trading Engine to shutdown bot!",
          e);
      throw new StrategyException(e);
    }
  }

  private void executeBuyPhase() throws TradingApiException, ExchangeNetworkException, StrategyException {
    LOG.info(() -> market.getName() + " BUY phase - check if the market moved up.");
    if (marketMovedUp()) {
      LOG.info(() -> market.getName() + " BUY phase - The market moved up. Place a BUY order on the exchange -->");
      final BigDecimal piecesToBuy = getAmountOfPiecesToBuy();

      String orderId = "DUMMY_ORDER_ID_" + UUID.randomUUID().toString();
      // TODO String orderId = tradingApi.createOrder(market.getId(), OrderType.BUY, piecesToBuy, currentMarketPrice);

      LOG.info(() -> market.getName() + " BUY Order sent successfully to exchange. ID: " + orderId);

      // update last order details
      currentOrder = new OrderState(orderId, OrderType.BUY, currentMarketPrice, piecesToBuy);
      strategyState = IntelligentStrategyState.WAIT_FOR_BUY;
    } else {
        LOG.info(() -> market.getName() + " BUY phase - The market gain needed to place a BUY order was not reached. Wait for the next trading strategy tick.");
    }
  }

  private boolean marketMovedUp() {
    // TODO take profit and loss counter into account
    BigDecimal currentPercentageGainNeededForBuy = configuredInitialPercentageGainNeededToPlaceBuyOrder;
    BigDecimal amountToMoveUp = lowestPrice.multiply(currentPercentageGainNeededForBuy);
    BigDecimal goalToReach = lowestPrice.add(amountToMoveUp);
    DecimalFormat decimalFormat = new DecimalFormat(DECIMAL_FORMAT);
    LOG.info(() -> market.getName() + " According to the minimum seen price '" + decimalFormat.format(lowestPrice) + " " + market.getCounterCurrency() + "'"
            + " and the current needed gain of '" + decimalFormat.format(currentPercentageGainNeededForBuy.multiply(new BigDecimal(100)))
            + "%', the price must go up '" + decimalFormat.format(amountToMoveUp) + " " + market.getCounterCurrency()
            +"' to " + decimalFormat.format(goalToReach) + " " + market.getCounterCurrency() + ". The current market price is '" + decimalFormat.format(currentMarketPrice) + " " + market.getCounterCurrency() + "'.");
    return currentMarketPrice.compareTo(goalToReach)>0;
  }

  private void updateMarketPrices() throws ExchangeNetworkException, TradingApiException {
    currentMarketPrice = tradingApi.getLatestMarketPrice(market.getId());
    LOG.info(() -> market.getName() + " Updated latest market price: " + new DecimalFormat(DECIMAL_FORMAT).format(currentMarketPrice) );
    if (lowestPrice == null) {
      LOG.info(() -> market.getName() + " Set first lowest price to "+ new DecimalFormat(DECIMAL_FORMAT).format(currentMarketPrice));
      lowestPrice = currentMarketPrice;
    } else if (currentMarketPrice.compareTo(lowestPrice) < 0 ) {
      LOG.info(() -> market.getName() + " Current market price is a new minimum price. Update lowest price from " + new DecimalFormat(DECIMAL_FORMAT).format(lowestPrice) + " to "+ new DecimalFormat(DECIMAL_FORMAT).format(currentMarketPrice));
      lowestPrice = currentMarketPrice;
    }
  }

  private void computeInitialStrategyState() {
    // TODO check for open orders and get order prices
    strategyState = IntelligentStrategyState.NEED_BUY;
  }

  private BigDecimal getAmountOfPiecesToBuy() throws TradingApiException, ExchangeNetworkException, StrategyException {
    final BigDecimal balanceToUseForBuyOrder = getBalanceToUseForBuyOrder();
    LOG.info(
        () ->
            market.getName()
                + " Calculating amount of base currency ("
                + market.getBaseCurrency()
                + ") to buy for amount of counter currency "
                + new DecimalFormat(DECIMAL_FORMAT).format(balanceToUseForBuyOrder)
                + " "
                + market.getCounterCurrency());

    /*
     * Most exchanges (if not all) use 8 decimal places and typically round in favour of the
     * exchange. It's usually safest to round down the order quantity in your calculations.
     */
    final BigDecimal amountOfPiecesInBaseCurrencyToBuy = balanceToUseForBuyOrder.divide(currentMarketPrice, 8, RoundingMode.HALF_DOWN);

    LOG.info(
        () ->
            market.getName()
                + " Amount of base currency ("
                + market.getBaseCurrency()
                + ") to BUY for "
                + new DecimalFormat(DECIMAL_FORMAT).format(balanceToUseForBuyOrder)
                + " "
                + market.getCounterCurrency()
                + " based on last market trade price: "
                + amountOfPiecesInBaseCurrencyToBuy);

    return amountOfPiecesInBaseCurrencyToBuy;
  }

  private BigDecimal getBalanceToUseForBuyOrder() throws ExchangeNetworkException, TradingApiException, StrategyException {
    LOG.info(() -> market.getName() + " Fetching the available balance for the counter currency "+ market.getCounterCurrency());
    BalanceInfo balanceInfo = tradingApi.getBalanceInfo();
    final BigDecimal currentBalance = balanceInfo.getBalancesAvailable().get(market.getCounterCurrency());
    if (currentBalance == null) {
      final String errorMsg = "Failed to get current counter currency balance as '"+ market.getCounterCurrency()+ "' key in the balances map. Balances returned: " + balanceInfo.getBalancesAvailable();
      LOG.error(() -> errorMsg);
      throw new StrategyException(errorMsg);
    } else {
      LOG.info(() ->market.getName() + "Counter Currency balance available on exchange is ["
              + new DecimalFormat(DECIMAL_FORMAT).format(currentBalance)
              + "] "
              + market.getCounterCurrency());
    }

    BigDecimal balanceAvailableForTrading = currentBalance.subtract(configuredEmergencyStop);
    if (balanceAvailableForTrading.compareTo(BigDecimal.ZERO)<=0) {
      String errorMsg = "No balance available for trading. When substracting the emergency stop, the remaining balance is " + new DecimalFormat(DECIMAL_FORMAT).format(balanceAvailableForTrading) + " " + market.getCounterCurrency();
      LOG.error(() -> market.getName() + errorMsg);
      throw new StrategyException(errorMsg);
    }
    LOG.info(() ->market.getName() + "Balance available after being reduced by the emergency stop: " + new DecimalFormat(DECIMAL_FORMAT).format(balanceAvailableForTrading) + " " + market.getCounterCurrency());
    BigDecimal balanceToUseForBuyOrder = balanceAvailableForTrading.multiply(configuredPercentageOfCounterCurrencyBalanceToUse);
    LOG.info(() ->market.getName() + "Balance to be used for trading, taking into consideration the configured trading percentage of "
            + new DecimalFormat(DECIMAL_FORMAT).format(configuredPercentageOfCounterCurrencyBalanceToUse) + ": " + new DecimalFormat(DECIMAL_FORMAT).format(balanceToUseForBuyOrder) + " " + market.getCounterCurrency());
    return balanceToUseForBuyOrder;
  }

  private void getConfigForStrategy(StrategyConfig config) {
    readInitialBuyPercentageGain(config);
    readEmergencyStopBalance(config);
    readPercentageOfCounterCurrencyBalanceToUse(config);
  }

  private void readEmergencyStopBalance(StrategyConfig config) {
    final String configuredEmergencyStopAsString =
            config.getConfigItem("configured-emergency-stop-balance");
    if (configuredEmergencyStopAsString == null) {
      throw new IllegalArgumentException(
              "Mandatory configuration for the emergency stop to be substracted from the available balance is missing or missing a value in the strategy.xml config.");
    }
    LOG.info(
            () ->
                    "<configured-emergency-stop-balance> from config is: " + configuredEmergencyStopAsString);

    // Will fail fast if value is not a number
    configuredEmergencyStop = new BigDecimal(configuredEmergencyStopAsString);
    LOG.info(() -> "configuredEmergencyStop is: " + configuredEmergencyStop);
  }

  private void readPercentageOfCounterCurrencyBalanceToUse(StrategyConfig config) {
    final String percentageToUseAsString =
            config.getConfigItem("percentage-of-counter-currency-balance-to-use");
    if (percentageToUseAsString == null) {
      // game over
      throw new IllegalArgumentException(
              "Mandatory percentage of counter currency balance to use for trading is missing a value in the strategy.xml config.");
    }
    LOG.info(
            () ->
                    "<percentage-of-counter-currency-balance-to-use> from config is: " + percentageToUseAsString);

    // Will fail fast if value is not a number
    final BigDecimal percentageOfBalanceToUseForTrading =
            new BigDecimal(percentageToUseAsString);
    configuredPercentageOfCounterCurrencyBalanceToUse = percentageOfBalanceToUseForTrading.divide(new BigDecimal(100), 8, RoundingMode.HALF_UP);

    LOG.info(() -> "percentageOfBalanceToUseForTrading in decimal is: " + configuredPercentageOfCounterCurrencyBalanceToUse);
  }

  private void readInitialBuyPercentageGain(StrategyConfig config) {
    final String initialBuyPercentageGainAsString =
        config.getConfigItem("initial-percentage-gain-needed-to-place-buy-order");
    if (initialBuyPercentageGainAsString == null) {
      // game over
      throw new IllegalArgumentException(
          "Mandatory initial-percentage-gain-needed-to-place-buy-order value missing in strategy.xml config.");
    }
    LOG.info(
        () ->
            "<initial-percentage-gain-needed-to-place-buy-order> from config is: " + initialBuyPercentageGainAsString);

    // Will fail fast if value is not a number
    final BigDecimal minimumPercentageGainFromConfig =
        new BigDecimal(initialBuyPercentageGainAsString);
    configuredInitialPercentageGainNeededToPlaceBuyOrder =
        minimumPercentageGainFromConfig.divide(new BigDecimal(100), 8, RoundingMode.HALF_UP);

    LOG.info(() -> "configuredInitialPercentageGainNeededToPlaceBuyOrder in decimal is: " + configuredInitialPercentageGainNeededToPlaceBuyOrder);
  }
}
