package io.trading.gateway.model;

import java.math.BigDecimal;

/**
 * Unified trade data model across all exchanges.
 *
 * @param exchange         The exchange identifier
 * @param symbol           Trading pair symbol (e.g., "BTCUSDT")
 * @param timestamp        Trade timestamp in milliseconds
 * @param gatewayTimestamp Gateway receive timestamp in nanoseconds
 * @param tradeId          Unique trade identifier
 * @param price            Trade price
 * @param quantity         Trade quantity
 * @param side             Trade side (BUY or SELL)
 */
public record Trade(
    Exchange exchange,
    String symbol,
    long timestamp,
    long gatewayTimestamp,
    String tradeId,
    BigDecimal price,
    BigDecimal quantity,
    Side side
) {
    public Trade {
        if (exchange == null) {
            throw new IllegalArgumentException("exchange cannot be null");
        }
        if (symbol == null || symbol.isEmpty()) {
            throw new IllegalArgumentException("symbol cannot be null or empty");
        }
        if (tradeId == null) {
            throw new IllegalArgumentException("tradeId cannot be null");
        }
        if (side == null) {
            throw new IllegalArgumentException("side cannot be null");
        }
    }
}
