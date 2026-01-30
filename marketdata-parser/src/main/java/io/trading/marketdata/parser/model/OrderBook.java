package io.trading.marketdata.parser.model;

import java.util.List;

/**
 * Unified order book data model across all exchanges.
 *
 * @param exchange         The exchange identifier
 * @param symbol           Trading pair symbol (e.g., "BTCUSDT")
 * @param timestamp        Exchange timestamp in milliseconds
 * @param gatewayTimestamp Gateway receive timestamp in nanoseconds
 * @param bids             Bid levels (sorted descending by price)
 * @param asks             Ask levels (sorted ascending by price)
 * @param isSnapshot       True if this is a full snapshot, false if it's an update
 */
public record OrderBook(
    Exchange exchange,
    String symbol,
    long timestamp,
    long gatewayTimestamp,
    List<OrderBookLevel> bids,
    List<OrderBookLevel> asks,
    boolean isSnapshot
) {
    public OrderBook {
        if (exchange == null) {
            throw new IllegalArgumentException("exchange cannot be null");
        }
        if (symbol == null || symbol.isEmpty()) {
            throw new IllegalArgumentException("symbol cannot be null or empty");
        }
        if (bids == null) {
            throw new IllegalArgumentException("bids cannot be null");
        }
        if (asks == null) {
            throw new IllegalArgumentException("asks cannot be null");
        }
    }
}
