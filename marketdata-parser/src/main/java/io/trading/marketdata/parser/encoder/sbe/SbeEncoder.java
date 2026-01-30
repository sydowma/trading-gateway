package io.trading.marketdata.parser.encoder.sbe;

import io.trading.marketdata.parser.encoder.MessageEncoder;
import io.trading.marketdata.parser.model.OrderBook;
import io.trading.marketdata.parser.model.Ticker;
import io.trading.marketdata.parser.model.Trade;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteOrder;

/**
 * SBE-style binary encoder for high-performance message encoding.
 * Uses a custom binary format compatible with Aeron publication.
 *
 * This encoder uses a fixed-size binary format:
 * - Message type: 1 byte
 * - Exchange: 1 byte enum
 * - Symbol: length-prefixed UTF-8 string
 * - Timestamps: 8 bytes each (big-endian)
 * - Prices/quantities: 8 bytes each (int64 scaled by 1e8)
 */
public class SbeEncoder implements MessageEncoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(SbeEncoder.class);

    // Decimal scale factor (8 decimal places for crypto precision)
    private static final long DECIMAL_SCALE = 100_000_000L;

    // Buffer sizes
    private static final int TICKER_SIZE = 128;
    private static final int TRADE_SIZE = 128;
    private static final int ORDER_BOOK_SIZE = 4096; // Larger for multiple levels

    // Pre-allocated reusable buffers
    private final ThreadLocal<MutableDirectBuffer> tickerBuffer =
            ThreadLocal.withInitial(() -> new UnsafeBuffer(new byte[TICKER_SIZE]));
    private final ThreadLocal<MutableDirectBuffer> tradeBuffer =
            ThreadLocal.withInitial(() -> new UnsafeBuffer(new byte[TRADE_SIZE]));
    private final ThreadLocal<MutableDirectBuffer> orderBookBuffer =
            ThreadLocal.withInitial(() -> new UnsafeBuffer(new byte[ORDER_BOOK_SIZE]));

    private static final SbeEncoder INSTANCE = new SbeEncoder();

    private SbeEncoder() {
        // Private constructor for singleton
    }

    /**
     * Gets the singleton instance.
     */
    public static SbeEncoder getInstance() {
        return INSTANCE;
    }

    @Override
    public byte[] encodeTickerToSbe(Ticker ticker) {
        MutableDirectBuffer buffer = tickerBuffer.get();
        int len = encodeTicker(buffer, ticker);
        byte[] result = new byte[len];
        buffer.getBytes(0, result, 0, len);
        return result;
    }

    @Override
    public byte[] encodeTradeToSbe(Trade trade) {
        MutableDirectBuffer buffer = tradeBuffer.get();
        int len = encodeTrade(buffer, trade);
        byte[] result = new byte[len];
        buffer.getBytes(0, result, 0, len);
        return result;
    }

    @Override
    public byte[] encodeOrderBookToSbe(OrderBook orderBook) {
        MutableDirectBuffer buffer = orderBookBuffer.get();
        int len = encodeOrderBook(buffer, orderBook);
        byte[] result = new byte[len];
        buffer.getBytes(0, result, 0, len);
        return result;
    }

    @Override
    public String encodeTickerToJson(Ticker ticker) {
        throw new UnsupportedOperationException("JSON encoding not supported by SbeEncoder");
    }

    @Override
    public String encodeTradeToJson(Trade trade) {
        throw new UnsupportedOperationException("JSON encoding not supported by SbeEncoder");
    }

    @Override
    public String encodeOrderBookToJson(OrderBook orderBook) {
        throw new UnsupportedOperationException("JSON encoding not supported by SbeEncoder");
    }

    // ==================== Internal Encoding Methods ====================

    private int encodeTicker(MutableDirectBuffer buffer, Ticker ticker) {
        int pos = 0;

        // Message type (1 byte)
        buffer.putByte(pos, (byte) 1); // MSG_TYPE_TICKER = 1
        pos += 1;

        // Exchange (1 byte enum)
        buffer.putByte(pos, (byte) ticker.exchange().ordinal());
        pos += 1;

        // Symbol (length-prefixed string)
        byte[] symbolBytes = ticker.symbol().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buffer.putByte(pos, (byte) Math.min(symbolBytes.length, 20));
        pos += 1;
        buffer.putBytes(pos, symbolBytes, 0, Math.min(symbolBytes.length, 20));
        pos += 20;

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

    private int encodeTrade(MutableDirectBuffer buffer, Trade trade) {
        int pos = 0;

        // Message type
        buffer.putByte(pos, (byte) 2); // MSG_TYPE_TRADE = 2
        pos += 1;

        // Exchange
        buffer.putByte(pos, (byte) trade.exchange().ordinal());
        pos += 1;

        // Symbol
        byte[] symbolBytes = trade.symbol().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buffer.putByte(pos, (byte) Math.min(symbolBytes.length, 20));
        pos += 1;
        buffer.putBytes(pos, symbolBytes, 0, Math.min(symbolBytes.length, 20));
        pos += 20;

        // Timestamps
        buffer.putLong(pos, trade.timestamp(), ByteOrder.BIG_ENDIAN);
        pos += 8;
        buffer.putLong(pos, trade.gatewayTimestamp(), ByteOrder.BIG_ENDIAN);
        pos += 8;

        // Trade ID
        byte[] tradeIdBytes = trade.tradeId().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buffer.putByte(pos, (byte) Math.min(tradeIdBytes.length, 32));
        pos += 1;
        buffer.putBytes(pos, tradeIdBytes, 0, Math.min(tradeIdBytes.length, 32));
        pos += 32;

        // Price and quantity
        buffer.putLong(pos, toInt64(trade.price()), ByteOrder.BIG_ENDIAN);
        pos += 8;
        buffer.putLong(pos, toInt64(trade.quantity()), ByteOrder.BIG_ENDIAN);
        pos += 8;

        // Side
        buffer.putByte(pos, (byte) trade.side().ordinal());
        pos += 1;

        return pos;
    }

    private int encodeOrderBook(MutableDirectBuffer buffer, OrderBook orderBook) {
        int pos = 0;

        // Message type
        buffer.putByte(pos, (byte) 3); // MSG_TYPE_ORDER_BOOK = 3
        pos += 1;

        // Exchange
        buffer.putByte(pos, (byte) orderBook.exchange().ordinal());
        pos += 1;

        // Symbol
        byte[] symbolBytes = orderBook.symbol().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buffer.putByte(pos, (byte) Math.min(symbolBytes.length, 20));
        pos += 1;
        buffer.putBytes(pos, symbolBytes, 0, Math.min(symbolBytes.length, 20));
        pos += 20;

        // Timestamps
        buffer.putLong(pos, orderBook.timestamp(), ByteOrder.BIG_ENDIAN);
        pos += 8;
        buffer.putLong(pos, orderBook.gatewayTimestamp(), ByteOrder.BIG_ENDIAN);
        pos += 8;

        // Count fields (1 byte each)
        int bidCount = Math.min(orderBook.bids().size(), 100);
        int askCount = Math.min(orderBook.asks().size(), 100);
        buffer.putByte(pos, (byte) bidCount);
        pos += 1;
        buffer.putByte(pos, (byte) askCount);
        pos += 1;

        // Snapshot flag
        buffer.putByte(pos, (byte) (orderBook.isSnapshot() ? 1 : 0));
        pos += 1;

        // Padding
        pos += 5; // Align to 8-byte boundary

        // Bid levels
        for (int i = 0; i < bidCount; i++) {
            var level = orderBook.bids().get(i);
            buffer.putLong(pos, toInt64(level.price()), ByteOrder.BIG_ENDIAN);
            pos += 8;
            buffer.putLong(pos, toInt64(level.quantity()), ByteOrder.BIG_ENDIAN);
            pos += 8;
        }

        // Ask levels
        for (int i = 0; i < askCount; i++) {
            var level = orderBook.asks().get(i);
            buffer.putLong(pos, toInt64(level.price()), ByteOrder.BIG_ENDIAN);
            pos += 8;
            buffer.putLong(pos, toInt64(level.quantity()), ByteOrder.BIG_ENDIAN);
            pos += 8;
        }

        return pos;
    }

    /**
     * Converts BigDecimal to int64 with 8 decimal places precision.
     */
    private static long toInt64(java.math.BigDecimal value) {
        if (value == null) {
            return 0L;
        }
        return value.multiply(java.math.BigDecimal.valueOf(DECIMAL_SCALE)).longValue();
    }
}
