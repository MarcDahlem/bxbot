package com.gazbert.bxbot.strategies;

import com.gazbert.bxbot.strategies.helper.IntelligentPriceTracker;
import com.gazbert.bxbot.strategies.helper.IntelligentStateTracker;
import com.gazbert.bxbot.strategies.helper.IntelligentStrategyState;
import com.gazbert.bxbot.trading.api.util.ta4j.ExitIndicator;
import com.gazbert.bxbot.trading.api.util.ta4j.MarketEnterType;
import com.gazbert.bxbot.strategy.api.StrategyConfig;
import com.gazbert.bxbot.strategy.api.StrategyException;
import com.gazbert.bxbot.strategy.api.TradingStrategy;
import com.gazbert.bxbot.trading.api.ExchangeNetworkException;
import com.gazbert.bxbot.trading.api.Market;
import com.gazbert.bxbot.trading.api.TradingApi;
import com.gazbert.bxbot.trading.api.TradingApiException;
import com.gazbert.bxbot.trading.api.util.JsonBarsSerializer;
import com.gazbert.bxbot.trading.api.util.ta4j.RecordedStrategy;
import com.gazbert.bxbot.trading.api.util.ta4j.Ta4j2Chart;

import java.math.BigDecimal;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.helpers.ConstantIndicator;

import java.io.File;
import java.util.Collection;

public abstract class AbstractIntelligentStrategy implements TradingStrategy {
    protected static final Logger LOG = LogManager.getLogger();
    protected Market market;
    protected TradingApi tradingApi;
    protected IntelligentPriceTracker priceTracker;
    protected IntelligentStateTracker stateTracker;

    private IntelligentStateTracker.OrderPriceCalculator enterPriceCalculator;
    private IntelligentStateTracker.OrderPriceCalculator exitPriceCalculator;
    private IntelligentStateTracker.OnTradeSuccessfullyClosedListener tradesObserver;
    private boolean shouldPersistTickerData;

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
    public final void init(TradingApi tradingApi, Market market, StrategyConfig config) {
        LOG.info(() -> "Initialising Trading Strategy...");
        this.market = market;
        this.tradingApi = tradingApi;
        priceTracker = new IntelligentPriceTracker(tradingApi, market, config);
        stateTracker = new IntelligentStateTracker(tradingApi, market, priceTracker, config);
        shouldPersistTickerData = StrategyConfigParser.readBoolean(config, "persist-ticker-data", false);

        try {
            enterPriceCalculator = createEnterPriceCalculator(config);
            exitPriceCalculator = createExitPriceCalculator(config);
            tradesObserver = createTradesObserver(config);
            botWillStartup(config);
            initLiveChartIndicators();
        } catch (TradingApiException | ExchangeNetworkException e) {
            String errorMsg = "Failed to initialize the concrete strategy implementation on startup.";
            LOG.error(() -> errorMsg);
            throw new IllegalStateException(errorMsg, e);
        }

        LOG.info(() -> "Trading Strategy initialised successfully!");
    }

    private void initLiveChartIndicators() throws TradingApiException, ExchangeNetworkException {
        RecordedStrategy recordedStrategy = stateTracker.getRecordedStrategy();
        Collection<Ta4j2Chart.ChartIndicatorConfig> indicatorConfigs = recordedStrategy.createChartIndicators();
        ExitIndicator exitPriceIndicator = createExitPriceIndicator();
        indicatorConfigs.add(new Ta4j2Chart.ChartIndicatorConfig(exitPriceIndicator, "calculated exit price", Ta4j2Chart.SELL_CURRENT_LIMIT_COLOR));
        indicatorConfigs.addAll(createStrategySpecificLiveChartIndicators());
        for (Ta4j2Chart.ChartIndicatorConfig config : indicatorConfigs) {
            priceTracker.addLivechartIndicatorConfig(config);
        }
    }

    private ExitIndicator createExitPriceIndicator() throws TradingApiException, ExchangeNetworkException {
        BarSeries series = priceTracker.getSeries();
        return new ExitIndicator(series,
                stateTracker.getBreakEvenIndicator(),
                entryIndex -> entryType -> index -> {
                    try {
                        BigDecimal currentExitPrice = this.exitPriceCalculator.calculate(entryType);
                        return new ConstantIndicator<>(series, series.numOf(currentExitPrice));
                    } catch (TradingApiException | ExchangeNetworkException | StrategyException e) {
                        e.printStackTrace();
                        throw new IllegalStateException(e);
                    }
                });
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
    public void execute() throws StrategyException, ExchangeNetworkException {

        try {

            priceTracker.updateMarketPrices();
            tradesObserver.logStatistics();
            IntelligentStrategyState strategyState = stateTracker.getCurrentState();

            switch (strategyState) {
                case NEED_ENTER:
                    executeEnterPhase();
                    break;
                case NEED_EXIT:
                    executeExitPhase();
                    break;
                case WAIT_FOR_ENTER:
                    executeCheckOfTheEnterOrder();
                    break;
                case WAIT_FOR_EXIT:
                    executeCheckOfTheExitOrder();
                    break;
                default:
                    throw new StrategyException("Unknown strategy state encounted: " + strategyState);
            }
        } catch (TradingApiException | StrategyException e) {
            // We are just going to re-throw as StrategyException for engine to deal with - it will
            // shutdown the bot.
            LOG.error(
                    market.getName()
                            + " Failed to perform the strategy because Exchange threw TradingApiException or StrategyException. "
                            + "Telling Trading Engine to shutdown bot!",
                    e);
            throw new StrategyException(e);
        } catch (ExchangeNetworkException e) {
            LOG.error(
                    market.getName()
                            + " Failed to perform the strategy because Exchange threw ExchangeNetworkexception"
                            + "Telling Trading Engine to shutdown bot!",
                    e);
            throw e;
        }
    }

    protected void showOverviewCharts() throws TradingApiException, ExchangeNetworkException {
        RecordedStrategy recordedStrategy = stateTracker.getRecordedStrategy();
        Collection<Ta4j2Chart.ChartIndicatorConfig> indicatorConfigs = recordedStrategy.createChartIndicators();
        indicatorConfigs.addAll(createStrategySpecificOverviewChartIndicators());

        Ta4j2Chart.printSeries(priceTracker.getSeries(), recordedStrategy, indicatorConfigs);
    }

    private void executeEnterPhase() throws TradingApiException, ExchangeNetworkException, StrategyException {
        LOG.info(() -> market.getName() + " ENTER phase - check if the market moved up.");
        if (shouldEnterMarket().isPresent()) {
            LOG.info(() -> market.getName() + " ENTER phase - The market did move. Place a ENTER order on the exchange -->");
            enterPriceCalculator.logStatistics(shouldEnterMarket().get());
            stateTracker.placeEnterOrder(enterPriceCalculator, shouldEnterMarket().get());
        } else {
            LOG.info(() -> market.getName() + " ENTER phase - The market movement needed to place an ENTER order was not reached. Wait for the next trading strategy tick.");
        }
    }

    private void executeExitPhase() throws TradingApiException, ExchangeNetworkException, StrategyException {
        LOG.info(() -> market.getName() + " EXIT phase - check if the market moved.");
        if (shouldExitMarket()) {
            LOG.info(() -> market.getName() + " EXIT phase - The market did move. Place a EXIT order on the exchange -->");
            stateTracker.placeExitOrder(exitPriceCalculator);
        } else {
            LOG.info(() -> market.getName() + " EXIT phase - The market movevement needed to place an EXIT order was not reached. Wait for the next trading strategy tick.");
        }

    }

    private void executeCheckOfTheEnterOrder() throws TradingApiException, ExchangeNetworkException, StrategyException {
        LOG.info(() -> market.getName() + " State: Wait for ENTER order to fulfill.");
        stateTracker.trackRunningEnterOrder(enterPriceCalculator);
    }

    private void executeCheckOfTheExitOrder() throws TradingApiException, ExchangeNetworkException, StrategyException {
        LOG.info(() -> market.getName() + " State: Wait for EXIT order to fulfill.");
        exitPriceCalculator.logStatistics(stateTracker.getCurrentMarketEntry());
        stateTracker.trackRunningExitOrder(exitPriceCalculator, tradesObserver);
    }

    @Override
    public void saveState() {
        if (shouldPersistTickerData) {
            JsonBarsSerializer.persistSeries(priceTracker.getSeries(), "recordedMarketDataOhlc" + File.separator + "5min_" + market.getId() + System.currentTimeMillis() + ".json");
        }
        try {
            showOverviewCharts();
            botWillShutdown();
        } catch (Exception e1) {
            String errorMsg = "Failed to shutdown the concrete strategy implementation.";
            LOG.error(() -> errorMsg, e1);
        }
    }

    protected abstract void botWillStartup(StrategyConfig config) throws TradingApiException, ExchangeNetworkException;

    protected abstract Collection<? extends Ta4j2Chart.ChartIndicatorConfig> createStrategySpecificLiveChartIndicators() throws TradingApiException, ExchangeNetworkException;

    protected abstract IntelligentStateTracker.OrderPriceCalculator createExitPriceCalculator(StrategyConfig config) throws TradingApiException, ExchangeNetworkException;

    protected abstract IntelligentStateTracker.OrderPriceCalculator createEnterPriceCalculator(StrategyConfig config);

    protected abstract IntelligentStateTracker.OnTradeSuccessfullyClosedListener createTradesObserver(StrategyConfig config);

    protected abstract Optional<MarketEnterType> shouldEnterMarket() throws TradingApiException, ExchangeNetworkException;

    protected abstract boolean shouldExitMarket() throws TradingApiException, ExchangeNetworkException;

    protected abstract Collection<? extends Ta4j2Chart.ChartIndicatorConfig> createStrategySpecificOverviewChartIndicators() throws TradingApiException, ExchangeNetworkException;

    protected abstract void botWillShutdown() throws TradingApiException, ExchangeNetworkException;
}
