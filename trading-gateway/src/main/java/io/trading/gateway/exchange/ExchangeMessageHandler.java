package io.trading.gateway.exchange;

import io.trading.marketdata.parser.model.OrderBook;
import io.trading.marketdata.parser.model.Ticker;
import io.trading.marketdata.parser.model.Trade;

/**
 * Handler interface for parsed exchange messages.
 */
public interface ExchangeMessageHandler {

    /**
     * Called when a ticker message is received.
     */
    void onTicker(Ticker ticker);

    /**
     * Called when a trade message is received.
     */
    void onTrade(Trade trade);

    /**
     * Called when an order book message is received.
     */
    void onOrderBook(OrderBook orderBook);
}
