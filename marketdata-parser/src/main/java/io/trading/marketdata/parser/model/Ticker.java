package io.trading.marketdata.parser.model;

import java.math.BigDecimal;

/**
 * Unified ticker data model across all exchanges.
 *
 * @param exchange         The exchange identifier
 * @param symbol           Trading pair symbol (e.g., "BTCUSDT")
 * @param timestamp        Exchange timestamp in milliseconds
 * @param gatewayTimestamp Gateway receive timestamp in nanoseconds
 * @param lastPrice        Last traded price
 * @param bidPrice         Best bid price
 * @param askPrice         Best ask price
 * @param bidQuantity      Best bid quantity
 * @param askQuantity      Best ask quantity
 * @param volume24h        24-hour volume
 * @param change24h        24-hour price change
 * @param changePercent24h 24-hour price change percentage
 */
public record Ticker(
    Exchange exchange,
    String symbol,
    long timestamp,
    long gatewayTimestamp,
    BigDecimal lastPrice,
    BigDecimal bidPrice,
    BigDecimal askPrice,
    BigDecimal bidQuantity,
    BigDecimal askQuantity,
    BigDecimal volume24h,
    BigDecimal change24h,
    BigDecimal changePercent24h
) {
    public Ticker {
        if (exchange == null) {
            throw new IllegalArgumentException("exchange cannot be null");
        }
        if (symbol == null || symbol.isEmpty()) {
            throw new IllegalArgumentException("symbol cannot be null or empty");
        }
    }
}
