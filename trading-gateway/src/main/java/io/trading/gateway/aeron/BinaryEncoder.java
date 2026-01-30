package io.trading.gateway.aeron;

import io.trading.marketdata.parser.model.Exchange;
import io.trading.marketdata.parser.model.OrderBook;
import io.trading.marketdata.parser.model.OrderBookLevel;
import io.trading.marketdata.parser.model.Side;
import io.trading.marketdata.parser.model.Ticker;
import io.trading.marketdata.parser.model.Trade;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.math.BigDecimal;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * High-performance binary message encoder for Aeron publication.
 *
 * This uses a custom binary format inspired by SBE principles:
 * - Fixed-size fields for primitive types
 * - Big-endian byte order (network byte order)
 * - Direct buffer access for zero-copy Aeron publication
 * - No runtime reflection or object allocation
 *
 * Binary Format:
 * - Message starts with 1-byte message type (1=Ticker, 2=Trade, 3=OrderBook)
 * - All prices/quantities are encoded as int64 with implicit 8 decimal places
 * - Strings are encoded as: 1-byte length + UTF-8 bytes
 *
 * Encoding scheme for decimals:
 * - BigDecimal values are scaled by 1e8 for storage
 * - e.g., 50000.00 -> 5000000000000L
 */
public final class BinaryEncoder {

    // Message type constants
    public static final byte MSG_TYPE_TICKER = 1;
    public static final byte MSG_TYPE_TRADE = 2;
    public static final byte MSG_TYPE_ORDER_BOOK = 3;

    // Decimal scale factor (8 decimal places for crypto precision)
    private static final long DECIMAL_SCALE = 100_000_000L;

    // Buffer sizes
    private static final int TICKER_SIZE = 128;
    private static final int TRADE_SIZE = 128;
    private static final int ORDER_BOOK_SIZE = 512;

    // Pre-allocated reusable buffers
    private static final AtomicBuffer TICKER_BUFFER = new UnsafeBuffer(new byte[TICKER_SIZE]);
    private static final AtomicBuffer TRADE_BUFFER = new UnsafeBuffer(new byte[TRADE_SIZE]);
    private static final AtomicBuffer ORDER_BOOK_BUFFER = new UnsafeBuffer(new byte[ORDER_BOOK_SIZE]);

    private BinaryEncoder() {}

    // ==================== Ticker Encoding ====================

    /**
     * Encodes a Ticker to the provided buffer.
     * @return The encoded length
     */
    public static int encodeTicker(MutableDirectBuffer buffer, Ticker ticker) {
        int pos = 0;

        // Message type
        buffer.putByte(pos, MSG_TYPE_TICKER);
        pos += 1;

        // Exchange (1 byte enum)
        buffer.putByte(pos, (byte) ticker.exchange().ordinal());
        pos += 1;

        // Symbol (length-prefixed string)
        byte[] symbolBytes = ticker.symbol().getBytes(StandardCharsets.UTF_8);
        buffer.putByte(pos, (byte) symbolBytes.length);
        pos += 1;
        buffer.putBytes(pos, symbolBytes);
        pos += Math.min(symbolBytes.length, 20); // Max 20 chars

        // Timestamps (8 bytes each, big-endian)
        buffer.putLong(pos, ticker.timestamp(), ByteOrder.BIG_ENDIAN);
        pos += 8;
        buffer.putLong(pos, ticker.gatewayTimestamp(), ByteOrder.BIG_ENDIAN);
        pos += 8;

        // Prices (8 bytes each, scaled by 1e8)
        buffer.putLong(pos, toInt64(ticker.lastPrice()), ByteOrder.BIG_ENDIAN);
        pos += 8;
        buffer.putLong(pos, toInt64(ticker.bidPrice()), ByteOrder.BIG_ENDIAN);
        pos += 8;
        buffer.putLong(pos, toInt64(ticker.askPrice()), ByteOrder.BIG_ENDIAN);
        pos += 8;
        buffer.putLong(pos, toInt64(ticker.bidQuantity()), ByteOrder.BIG_ENDIAN);
        pos += 8;
        buffer.putLong(pos, toInt64(ticker.askQuantity()), ByteOrder.BIG_ENDIAN);
        pos += 8;

        // 24h stats
        buffer.putLong(pos, toInt64(ticker.volume24h()), ByteOrder.BIG_ENDIAN);
        pos += 8;
        buffer.putLong(pos, toInt64(ticker.change24h()), ByteOrder.BIG_ENDIAN);
        pos += 8;
        buffer.putLong(pos, toInt64(ticker.changePercent24h()), ByteOrder.BIG_ENDIAN);
        pos += 8;

        return pos;
    }

    /**
     * Encodes a Ticker to a new byte array.
     */
    public static byte[] encodeTicker(Ticker ticker) {
        int len = encodeTicker(TICKER_BUFFER, ticker);
        byte[] result = new byte[len];
        TICKER_BUFFER.getBytes(0, result, 0, len);
        return result;
    }

    // ==================== Trade Encoding ====================

    /**
     * Encodes a Trade to the provided buffer.
     * @return The encoded length
     */
    public static int encodeTrade(MutableDirectBuffer buffer, Trade trade) {
        int pos = 0;

        // Message type
        buffer.putByte(pos, MSG_TYPE_TRADE);
        pos += 1;

        // Exchange (1 byte)
        buffer.putByte(pos, (byte) trade.exchange().ordinal());
        pos += 1;

        // Symbol (length-prefixed)
        byte[] symbolBytes = trade.symbol().getBytes(StandardCharsets.UTF_8);
        buffer.putByte(pos, (byte) symbolBytes.length);
        pos += 1;
        buffer.putBytes(pos, symbolBytes);
        pos += Math.min(symbolBytes.length, 20);

        // Timestamps
        buffer.putLong(pos, trade.timestamp(), ByteOrder.BIG_ENDIAN);
        pos += 8;
        buffer.putLong(pos, trade.gatewayTimestamp(), ByteOrder.BIG_ENDIAN);
        pos += 8;

        // Trade ID (length-prefixed)
        byte[] tradeIdBytes = trade.tradeId().getBytes(StandardCharsets.UTF_8);
        buffer.putByte(pos, (byte) tradeIdBytes.length);
        pos += 1;
        buffer.putBytes(pos, tradeIdBytes);
        pos += Math.min(tradeIdBytes.length, 32);

        // Price and quantity
        buffer.putLong(pos, toInt64(trade.price()), ByteOrder.BIG_ENDIAN);
        pos += 8;
        buffer.putLong(pos, toInt64(trade.quantity()), ByteOrder.BIG_ENDIAN);
        pos += 8;

        // Side (1 byte)
        buffer.putByte(pos, (byte) trade.side().ordinal());

        return pos + 1;
    }

    /**
     * Encodes a Trade to a new byte array.
     */
    public static byte[] encodeTrade(Trade trade) {
        int len = encodeTrade(TRADE_BUFFER, trade);
        byte[] result = new byte[len];
        TRADE_BUFFER.getBytes(0, result, 0, len);
        return result;
    }

    // ==================== OrderBook Encoding ====================

    /**
     * Encodes an OrderBook to the provided buffer.
     * @return The encoded length
     */
    public static int encodeOrderBook(MutableDirectBuffer buffer, OrderBook orderBook) {
        int pos = 0;

        // Message type
        buffer.putByte(pos, MSG_TYPE_ORDER_BOOK);
        pos += 1;

        // Exchange
        buffer.putByte(pos, (byte) orderBook.exchange().ordinal());
        pos += 1;

        // Symbol
        byte[] symbolBytes = orderBook.symbol().getBytes(StandardCharsets.UTF_8);
        buffer.putByte(pos, (byte) symbolBytes.length);
        pos += 1;
        buffer.putBytes(pos, symbolBytes);
        pos += Math.min(symbolBytes.length, 20);

        // Timestamps
        buffer.putLong(pos, orderBook.timestamp(), ByteOrder.BIG_ENDIAN);
        pos += 8;
        buffer.putLong(pos, orderBook.gatewayTimestamp(), ByteOrder.BIG_ENDIAN);
        pos += 8;

        // Count fields
        buffer.putByte(pos, (byte) orderBook.bids().size());
        pos += 1;
        buffer.putByte(pos, (byte) orderBook.asks().size());
        pos += 1;

        // Snapshot flag
        buffer.putByte(pos, (byte) (orderBook.isSnapshot() ? 1 : 0));
        pos += 1;

        // Bid levels (max 10)
        int bidCount = Math.min(orderBook.bids().size(), 10);
        for (int i = 0; i < bidCount; i++) {
            OrderBookLevel level = orderBook.bids().get(i);
            buffer.putLong(pos, toInt64(level.price()), ByteOrder.BIG_ENDIAN);
            pos += 8;
            buffer.putLong(pos, toInt64(level.quantity()), ByteOrder.BIG_ENDIAN);
            pos += 8;
        }

        // Ask levels (max 10)
        int askCount = Math.min(orderBook.asks().size(), 10);
        for (int i = 0; i < askCount; i++) {
            OrderBookLevel level = orderBook.asks().get(i);
            buffer.putLong(pos, toInt64(level.price()), ByteOrder.BIG_ENDIAN);
            pos += 8;
            buffer.putLong(pos, toInt64(level.quantity()), ByteOrder.BIG_ENDIAN);
            pos += 8;
        }

        return pos;
    }

    /**
     * Encodes an OrderBook to a new byte array.
     */
    public static byte[] encodeOrderBook(OrderBook orderBook) {
        int len = encodeOrderBook(ORDER_BOOK_BUFFER, orderBook);
        byte[] result = new byte[len];
        ORDER_BOOK_BUFFER.getBytes(0, result, 0, len);
        return result;
    }

    // ==================== Reusable Buffers ====================

    public static AtomicBuffer getTickerBuffer() {
        return new UnsafeBuffer(new byte[TICKER_SIZE]);
    }

    public static AtomicBuffer getTradeBuffer() {
        return new UnsafeBuffer(new byte[TRADE_SIZE]);
    }

    public static AtomicBuffer getOrderBookBuffer() {
        return new UnsafeBuffer(new byte[ORDER_BOOK_SIZE]);
    }

    // ==================== Utility Methods ====================

    /**
     * Converts BigDecimal to int64 with 8 decimal places precision.
     */
    private static long toInt64(BigDecimal value) {
        if (value == null) {
            return 0L;
        }
        return value.multiply(BigDecimal.valueOf(DECIMAL_SCALE)).longValue();
    }

    /**
     * Decodes int64 back to BigDecimal (for consumer/testing).
     */
    public static BigDecimal fromInt64(long value) {
        return BigDecimal.valueOf(value, 8);
    }
}
