package com.gazbert.bxbot.strategies;

import com.gazbert.bxbot.strategies.helper.IntelligentPriceTracker;
import com.gazbert.bxbot.strategies.helper.IntelligentStateTracker;
import com.gazbert.bxbot.strategies.helper.IntelligentStrategyState;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.strategy.api.TradingStrategy;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.Market;
import com.gazbert.bxbot.trading.api.TradingApi;
import com.gazbert.bxbot.trading.api.TradingApiException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public abstract class AbstractIntelligentStrategy implements TradingStrategy {
    protected static final Logger LOG = LogManager.getLogger();
    protected Market market;
    protected TradingApi tradingApi;
    protected IntelligentPriceTracker priceTracker;
    protected IntelligentStateTracker stateTracker;

    private IntelligentStateTracker.OrderPriceCalculator buyPriceCalculator;
    private IntelligentStateTracker.OrderPriceCalculator sellPriceCalculator;
    private IntelligentStateTracker.OnTradeSuccessfullyClosedListener tradesObserver;

    /**
     * Initialises the Trading Strategy. Called once by the Trading Engine when the bot starts up;
     * it's a bit like a servlet init() method.
     *
     * @param tradingApi the Trading API. Use this to make trades and stuff.
     * @param market     the market for this strategy. This is the market the strategy is currently
     *                   running on - you wire this up in the markets.yaml and strategies.yaml files.
     * @param config     configuration for the strategy. Contains any (optional) config you set up in the
     *                   strategies.yaml file.
     */
    @Override
    public void init(TradingApi tradingApi, Market market, StrategyConfig config) {
        LOG.info(() -> "Initialising Trading Strategy...");
        this.market = market;
        this.tradingApi = tradingApi;
        priceTracker = new IntelligentPriceTracker(tradingApi, market);
        stateTracker = new IntelligentStateTracker(tradingApi, market, priceTracker, config);
        buyPriceCalculator = createBuyPriceCalculator(config);
        sellPriceCalculator = createSellPriceCalculator(config);
        tradesObserver = createTradesObserver(config);

        LOG.info(() -> "Trading Strategy initialised successfully!");
    }

    /**
     * This is the main execution method of the Trading Strategy. It is where your algorithm lives.
     *
     * <p>It is called by the Trading Engine during each trade cycle, e.g. every 60s. The trade cycle
     * is configured in the {project-root}/config/engine.yaml file.
     *
     * @throws StrategyException if something unexpected occurs. This tells the Trading Engine to
     *                           shutdown the bot immediately to help prevent unexpected losses.
     */
    @Override
    public void execute() throws StrategyException {

        try {

            priceTracker.updateMarketPrices();
            tradesObserver.logStatistics();
            IntelligentStrategyState strategyState = stateTracker.getCurrentState();

            switch (strategyState) {
                case NEED_BUY:
                    executeBuyPhase();
                    break;
                case NEED_SELL:
                    executeSellPhase();
                    break;
                case WAIT_FOR_BUY:
                    executeCheckOfTheBuyOrder();
                    break;
                case WAIT_FOR_SELL:
                    executeCheckOfTheSellOrder();
                    break;
                default:
                    throw new StrategyException("Unknown strategy state encounted: " + strategyState);
            }
        } catch (TradingApiException | ExchangeNetworkException | StrategyException e) {
            // We are just going to re-throw as StrategyException for engine to deal with - it will
            // shutdown the bot.
            // TODO reanable JsonBarsSerializer.persistSeries(series, market.getId() + System.currentTimeMillis() + ".json");
            onClose();
            LOG.error(
                    market.getName()
                            + " Failed to perform the strategy because Exchange threw TradingApiException, ExchangeNetworkexception or StrategyException. "
                            + "Telling Trading Engine to shutdown bot!",
                    e);
            throw new StrategyException(e);
        }
    }

    protected abstract void onClose();

    private void executeBuyPhase() throws TradingApiException, ExchangeNetworkException, StrategyException {
        LOG.info(() -> market.getName() + " BUY phase - check if the market moved up.");
        buyPriceCalculator.logStatistics();
        if (marketMovedUp()) {
            LOG.info(() -> market.getName() + " BUY phase - The market moved up. Place a BUY order on the exchange -->");
            stateTracker.placeBuyOrder(buyPriceCalculator);
        } else {
            LOG.info(() -> market.getName() + " BUY phase - The market gain needed to place a BUY order was not reached. Wait for the next trading strategy tick.");
        }
    }

    private void executeSellPhase() throws TradingApiException, ExchangeNetworkException, StrategyException {
        LOG.info(() -> market.getName() + " SELL phase - create a SELL order for the last sucessfull BUY.");
        stateTracker.placeSellOrder(sellPriceCalculator);
    }

    private void executeCheckOfTheBuyOrder() throws TradingApiException, ExchangeNetworkException, StrategyException {
        LOG.info(() -> market.getName() + " State: Wait for BUY order to fulfill.");
        stateTracker.trackRunningBuyOrder(newState -> {
            switch (newState) {
                case NEED_SELL:
                    executeSellPhase();
                    break;
                case NEED_BUY:
                    executeBuyPhase();
                    break;
                default:
                    throw new StrategyException("Invalid state encountered: " + newState + ". No idea how to proceed...");
            }
        });
    }

    private void executeCheckOfTheSellOrder() throws TradingApiException, ExchangeNetworkException, StrategyException {
        LOG.info(() -> market.getName() + " State: Wait for SELL order to fulfill.");
        sellPriceCalculator.logStatistics();
        stateTracker.trackRunningSellOrder(sellPriceCalculator, newState -> {
            switch (newState) {
                case NEED_SELL:
                    executeSellPhase();
                    break;
                case NEED_BUY:
                    executeBuyPhase();
                    break;
                default:
                    throw new StrategyException("Invalid state encountered: " + newState + ". No idea how to proceed...");
            }
        }, tradesObserver);
    }

    protected abstract IntelligentStateTracker.OrderPriceCalculator createSellPriceCalculator(StrategyConfig config);

    protected abstract IntelligentStateTracker.OrderPriceCalculator createBuyPriceCalculator(StrategyConfig config);

    protected abstract IntelligentStateTracker.OnTradeSuccessfullyClosedListener createTradesObserver(StrategyConfig config);

    protected abstract boolean marketMovedUp();
}
