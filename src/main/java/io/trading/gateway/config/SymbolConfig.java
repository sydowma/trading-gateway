package io.trading.gateway.config;

import io.trading.gateway.model.Exchange;

import java.util.Set;

/**
 * Configuration for a single trading symbol across multiple exchanges.
 *
 * @param symbol    Trading pair symbol (e.g., "BTCUSDT")
 * @param exchanges Set of exchanges to subscribe for this symbol
 */
public record SymbolConfig(
    String symbol,
    Set<Exchange> exchanges
) {
    public SymbolConfig {
        if (symbol == null || symbol.isEmpty()) {
            throw new IllegalArgumentException("symbol cannot be null or empty");
        }
        if (exchanges == null || exchanges.isEmpty()) {
            throw new IllegalArgumentException("exchanges cannot be null or empty");
        }
    }

    /**
     * Parses a symbol configuration string.
     * Format: "SYMBOL:exchange1,exchange2,exchange3"
     * Example: "BTCUSDT:binance,okx,bybit"
     */
    public static SymbolConfig fromString(String value) {
        String[] parts = value.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid symbol config format: " + value);
        }

        String symbol = parts[0].trim();
        Set<Exchange> exchanges = java.util.Arrays.stream(parts[1].split(","))
            .map(String::trim)
            .map(String::toUpperCase)
            .map(Exchange::valueOf)
            .collect(java.util.stream.Collectors.toSet());

        return new SymbolConfig(symbol, exchanges);
    }
}
