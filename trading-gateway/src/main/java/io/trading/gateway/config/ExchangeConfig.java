package io.trading.gateway.config;

import io.trading.marketdata.parser.model.DataType;
import io.trading.marketdata.parser.model.Exchange;

import java.util.Set;

/**
 * Configuration for a single exchange.
 *
 * @param exchange     The exchange identifier
 * @param enabled      Whether this exchange is enabled
 * @param dataTypes    Set of data types to subscribe (ticker, trades, order_book)
 */
public record ExchangeConfig(
    Exchange exchange,
    boolean enabled,
    Set<DataType> dataTypes
) {
    public ExchangeConfig {
        if (exchange == null) {
            throw new IllegalArgumentException("exchange cannot be null");
        }
        if (dataTypes == null || dataTypes.isEmpty()) {
            throw new IllegalArgumentException("dataTypes cannot be null or empty");
        }
    }

    /**
     * Parses an exchange configuration string.
     * Format: "exchange:true:ticker,trade,book"
     * Example: "binance:true:ticker,trade,book"
     */
    public static ExchangeConfig fromString(String value) {
        String[] parts = value.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid exchange config format: " + value);
        }

        Exchange exchange = Exchange.valueOf(parts[0].trim().toUpperCase());
        boolean enabled = Boolean.parseBoolean(parts[1].trim());

        Set<DataType> dataTypes = java.util.Arrays.stream(parts[2].split(","))
            .map(String::trim)
            .map(type -> switch (type.toLowerCase()) {
                case "ticker" -> DataType.TICKER;
                case "trade" -> DataType.TRADES;
                case "book" -> DataType.ORDER_BOOK;
                default -> throw new IllegalArgumentException("Unknown data type: " + type);
            })
            .collect(java.util.stream.Collectors.toSet());

        return new ExchangeConfig(exchange, enabled, dataTypes);
    }
}
