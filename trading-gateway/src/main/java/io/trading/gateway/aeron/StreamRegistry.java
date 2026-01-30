package io.trading.gateway.aeron;

import io.trading.marketdata.parser.model.DataType;
import io.trading.marketdata.parser.model.Exchange;

/**
 * Registry for managing Aeron stream IDs.
 *
 * Stream ID allocation strategy:
 * Base: 1000
 * Binance Ticker:    1001 (BASE + 0*10 + 1)
 * Binance Trades:    1002 (BASE + 0*10 + 2)
 * Binance OrderBook: 1003 (BASE + 0*10 + 3)
 * OKX Ticker:        1011 (BASE + 1*10 + 1)
 * OKX Trades:        1012
 * OKX OrderBook:     1013
 * Bybit Ticker:      1021 (BASE + 2*10 + 1)
 * Bybit Trades:      1022
 * Bybit OrderBook:   1023
 */
public class StreamRegistry {

    private static final int BASE_STREAM_ID = 1000;

    /**
     * Gets the stream ID for a given exchange and data type.
     *
     * @param exchange The exchange
     * @param dataType The data type
     * @return The allocated stream ID
     */
    public static int getStreamId(Exchange exchange, DataType dataType) {
        int exchangeOffset = exchange.ordinal() * 10;
        int typeOffset = switch (dataType) {
            case TICKER -> 1;
            case TRADES -> 2;
            case ORDER_BOOK -> 3;
            case UNKNOWN -> 0;
        };
        return BASE_STREAM_ID + exchangeOffset + typeOffset;
    }

    /**
     * Gets the Aeron channel URI for a given exchange and data type.
     *
     * @param exchange The exchange
     * @param dataType The data type
     * @return The Aeron channel URI
     */
    public static String getChannel(Exchange exchange, DataType dataType) {
        int streamId = getStreamId(exchange, dataType);
        String alias = String.format("gateway-%s-%s",
            exchange.name().toLowerCase(),
            dataType.getDisplayName()
        );

        return String.format(
            "aeron:ipc?term-length=128k|alias=%s|session-id=%d",
            alias,
            streamId
        );
    }

    /**
     * Gets the session ID for a given exchange and data type.
     * The session ID is the same as the stream ID for deterministic mapping.
     *
     * @param exchange The exchange
     * @param dataType The data type
     * @return The session ID
     */
    public static int getSessionId(Exchange exchange, DataType dataType) {
        return getStreamId(exchange, dataType);
    }
}
