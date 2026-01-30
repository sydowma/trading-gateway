package io.trading.marketdata.parser.api;

import io.trading.marketdata.parser.model.DataType;

/**
 * Interface for parsing WebSocket messages from cryptocurrency exchanges.
 * Implementations should be optimized for high-performance parsing with minimal allocations.
 */
public interface MarketDataParser {

    /**
     * Parses a WebSocket message and returns the result in the specified format.
     *
     * @param message The raw JSON message from the exchange
     * @param format  The desired output format
     * @return ParseResult containing the parsed data
     * @throws IllegalArgumentException if the message is invalid
     */
    ParseResult parse(String message, OutputFormat format);

    /**
     * Determines if the message is a ticker message.
     *
     * @param message The raw JSON message
     * @return true if the message is a ticker update
     */
    boolean isTicker(String message);

    /**
     * Determines if the message is a trade message.
     *
     * @param message The raw JSON message
     * @return true if the message is a trade update
     */
    boolean isTrade(String message);

    /**
     * Determines if the message is an order book message.
     *
     * @param message The raw JSON message
     * @return true if the message is an order book update
     */
    boolean isOrderBook(String message);

    /**
     * Determines the data type of the message.
     *
     * @param message The raw JSON message
     * @return The data type (TICKER, TRADE, ORDER_BOOK, or UNKNOWN)
     */
    default DataType parseMessageType(String message) {
        if (isTicker(message)) {
            return DataType.TICKER;
        } else if (isTrade(message)) {
            return DataType.TRADES;
        } else if (isOrderBook(message)) {
            return DataType.ORDER_BOOK;
        }
        return DataType.UNKNOWN;
    }
}
