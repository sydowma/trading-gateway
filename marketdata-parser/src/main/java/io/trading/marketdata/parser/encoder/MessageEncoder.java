package io.trading.marketdata.parser.encoder;

import io.trading.marketdata.parser.model.OrderBook;
import io.trading.marketdata.parser.model.Ticker;
import io.trading.marketdata.parser.model.Trade;

/**
 * Interface for encoding market data objects to different formats.
 * Implementations should be optimized for high-performance encoding.
 */
public interface MessageEncoder {

    /**
     * Encodes a ticker to SBE binary format.
     *
     * @param ticker The ticker to encode
     * @return SBE-encoded byte array
     */
    byte[] encodeTickerToSbe(Ticker ticker);

    /**
     * Encodes a trade to SBE binary format.
     *
     * @param trade The trade to encode
     * @return SBE-encoded byte array
     */
    byte[] encodeTradeToSbe(Trade trade);

    /**
     * Encodes an order book to SBE binary format.
     *
     * @param orderBook The order book to encode
     * @return SBE-encoded byte array
     */
    byte[] encodeOrderBookToSbe(OrderBook orderBook);

    /**
     * Encodes a ticker to JSON string.
     *
     * @param ticker The ticker to encode
     * @return JSON string
     */
    String encodeTickerToJson(Ticker ticker);

    /**
     * Encodes a trade to JSON string.
     *
     * @param trade The trade to encode
     * @return JSON string
     */
    String encodeTradeToJson(Trade trade);

    /**
     * Encodes an order book to JSON string.
     *
     * @param orderBook The order book to encode
     * @return JSON string
     */
    String encodeOrderBookToJson(OrderBook orderBook);
}
