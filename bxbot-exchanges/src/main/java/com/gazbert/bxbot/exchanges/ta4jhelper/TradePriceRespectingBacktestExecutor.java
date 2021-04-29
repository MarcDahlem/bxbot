package com.gazbert.bxbot.exchanges.ta4jhelper;

import org.ta4j.core.*;
import org.ta4j.core.cost.CostModel;
import org.ta4j.core.cost.ZeroCostModel;
import org.ta4j.core.num.Num;
import org.ta4j.core.reports.TradingStatement;
import org.ta4j.core.reports.TradingStatementGenerator;


import java.util.ArrayList;
import java.util.List;

public class TradePriceRespectingBacktestExecutor {

    private final TradingStatementGenerator tradingStatementGenerator;
    private final BarSeriesManager seriesManager;

    public TradePriceRespectingBacktestExecutor(BarSeries series, CostModel transactionCostModel) {
        this(series, new TradingStatementGenerator(), transactionCostModel);
    }

    public TradePriceRespectingBacktestExecutor(BarSeries series, TradingStatementGenerator tradingStatementGenerator, CostModel transactionCostModel) {
        this.seriesManager = new BarSeriesManager(series, transactionCostModel, new ZeroCostModel());
        this.tradingStatementGenerator = tradingStatementGenerator;
    }

    /**
     * Execute given strategies and return trading statements
     *
     * @param amount - The amount used to open/close the trades
     */
    public List<TradingStatement> execute(List<Strategy> strategies, Num amount) {
        return execute(strategies, amount, Trade.TradeType.BUY);
    }

    /**
     * Execute given strategies with specified order type to open trades and return
     * trading statements
     *
     * @param amount    - The amount used to open/close the trades
     * @param orderType the {@link Trade.TradeType} used to open the trades
     */
    public List<TradingStatement> execute(List<Strategy> strategies, Num amount, Trade.TradeType orderType) {
        final List<TradingStatement> tradingStatements = new ArrayList<>(strategies.size());
        for (Strategy strategy : strategies) {
            final TradingRecord tradingRecord = seriesManager.run(strategy, orderType, amount);
            final TradingStatement tradingStatement = tradingStatementGenerator.generate(strategy, tradingRecord,
                    seriesManager.getBarSeries());
            tradingStatements.add(tradingStatement);
        }
        return tradingStatements;
    }
}
