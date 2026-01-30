package io.trading.marketdata.parser.api;

import io.trading.marketdata.parser.model.DataType;
import io.trading.marketdata.parser.model.OrderBook;
import io.trading.marketdata.parser.model.Ticker;
import io.trading.marketdata.parser.model.Trade;

import java.util.Objects;

/**
 * Result of parsing a WebSocket message.
 * Contains the parsed data in the requested format along with metadata.
 */
public final class ParseResult {
    private final DataType dataType;
    private final OutputFormat format;
    private final Object data;
    private final long parseTimeNanos;

    // Cached encoded values (lazy initialization)
    private byte[] cachedSbeBytes;
    private String cachedJson;

    /**
     * Creates a new parse result.
     *
     * @param dataType      The type of market data (TICKER, TRADE, ORDER_BOOK)
     * @param format        The output format
     * @param data          The parsed data (Ticker, Trade, or OrderBook for JAVA format;
     *                      byte[] for SBE format; String for JSON format)
     * @param parseTimeNanos Time taken to parse in nanoseconds
     */
    public ParseResult(DataType dataType, OutputFormat format, Object data, long parseTimeNanos) {
        this.dataType = Objects.requireNonNull(dataType, "dataType cannot be null");
        this.format = Objects.requireNonNull(format, "format cannot be null");
        this.data = Objects.requireNonNull(data, "data cannot be null");
        this.parseTimeNanos = parseTimeNanos;
    }

    /**
     * Gets the data type.
     */
    public DataType getDataType() {
        return dataType;
    }

    /**
     * Gets the output format.
     */
    public OutputFormat getFormat() {
        return format;
    }

    /**
     * Gets the raw data object.
     * The type depends on the output format:
     * - JAVA: Ticker, Trade, or OrderBook
     * - SBE: byte[]
     * - JSON: String
     */
    public Object getData() {
        return data;
    }

    /**
     * Gets the parse time in nanoseconds.
     */
    public long getParseTimeNanos() {
        return parseTimeNanos;
    }

    /**
     * Gets the parse time in microseconds.
     */
    public double getParseTimeMicros() {
        return parseTimeNanos / 1000.0;
    }

    /**
     * Returns the data as a Ticker.
     *
     * @throws IllegalStateException if the data is not a Ticker
     */
    public Ticker getAsTicker() {
        if (!(data instanceof Ticker)) {
            throw new IllegalStateException("Data is not a Ticker, actual type: " + data.getClass());
        }
        return (Ticker) data;
    }

    /**
     * Returns the data as a Trade.
     *
     * @throws IllegalStateException if the data is not a Trade
     */
    public Trade getAsTrade() {
        if (!(data instanceof Trade)) {
            throw new IllegalStateException("Data is not a Trade, actual type: " + data.getClass());
        }
        return (Trade) data;
    }

    /**
     * Returns the data as an OrderBook.
     *
     * @throws IllegalStateException if the data is not an OrderBook
     */
    public OrderBook getAsOrderBook() {
        if (!(data instanceof OrderBook)) {
            throw new IllegalStateException("Data is not an OrderBook, actual type: " + data.getClass());
        }
        return (OrderBook) data;
    }

    /**
     * Returns the data as SBE-encoded bytes.
     * Only valid if format is SBE or if the data was encoded to SBE.
     */
    public byte[] getAsSbeBytes() {
        if (format == OutputFormat.SBE && data instanceof byte[]) {
            return (byte[]) data;
        }
        throw new IllegalStateException("Data is not in SBE format");
    }

    /**
     * Returns the data as a JSON string.
     * Only valid if format is JSON or if the data was encoded to JSON.
     */
    public String getAsJson() {
        if (format == OutputFormat.JSON && data instanceof String) {
            return (String) data;
        }
        throw new IllegalStateException("Data is not in JSON format");
    }

    /**
     * Sets the cached SBE bytes.
     * Used by encoders to cache the encoded result.
     */
    public void setCachedSbeBytes(byte[] sbeBytes) {
        this.cachedSbeBytes = sbeBytes;
    }

    /**
     * Gets the cached SBE bytes if available.
     */
    public byte[] getCachedSbeBytes() {
        return cachedSbeBytes;
    }

    /**
     * Sets the cached JSON string.
     * Used by encoders to cache the encoded result.
     */
    public void setCachedJson(String json) {
        this.cachedJson = json;
    }

    /**
     * Gets the cached JSON string if available.
     */
    public String getCachedJson() {
        return cachedJson;
    }

    @Override
    public String toString() {
        return "ParseResult{" +
                "dataType=" + dataType +
                ", format=" + format +
                ", parseTimeNanos=" + parseTimeNanos +
                ", parseTimeMicros=" + String.format("%.2f", getParseTimeMicros()) +
                '}';
    }
}
