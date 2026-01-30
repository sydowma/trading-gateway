package io.trading.gateway.aeron;

import io.trading.marketdata.parser.model.Exchange;
import io.trading.marketdata.parser.model.OrderBook;
import io.trading.marketdata.parser.model.OrderBookLevel;
import io.trading.marketdata.parser.model.Side;
import io.trading.marketdata.parser.model.Ticker;
import io.trading.marketdata.parser.model.Trade;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.math.BigDecimal;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Binary message decoder for consuming market data from Aeron.
 *
 * Decodes messages encoded by BinaryEncoder.
 */
public final class BinaryDecoder {

    private static final long DECIMAL_SCALE = 100_000_000L;

    private BinaryDecoder() {}

    // ==================== Message Type Detection ====================

    public static byte getMessageType(DirectBuffer buffer, int offset) {
        return buffer.getByte(offset);
    }

    // ==================== Ticker Decoding ====================

    /**
     * Decodes a Ticker from buffer.
     */
    public static Ticker decodeTicker(DirectBuffer buffer, int offset) {
        int pos = offset;

        // Skip message type (already read)
        pos += 1;

        // Exchange
        Exchange exchange = Exchange.values()[buffer.getByte(pos)];
        pos += 1;

        // Symbol
        int symbolLen = buffer.getByte(pos) & 0xFF;
        pos += 1;
        String symbol = getString(buffer, pos, symbolLen);
        pos += symbolLen;

        // Timestamps
        long timestamp = buffer.getLong(pos, ByteOrder.BIG_ENDIAN);
        pos += 8;
        long gatewayTimestamp = buffer.getLong(pos, ByteOrder.BIG_ENDIAN);
        pos += 8;

        // Prices
        BigDecimal lastPrice = fromInt64(buffer.getLong(pos, ByteOrder.BIG_ENDIAN));
        pos += 8;
        BigDecimal bidPrice = fromInt64(buffer.getLong(pos, ByteOrder.BIG_ENDIAN));
        pos += 8;
        BigDecimal askPrice = fromInt64(buffer.getLong(pos, ByteOrder.BIG_ENDIAN));
        pos += 8;
        BigDecimal bidQuantity = fromInt64(buffer.getLong(pos, ByteOrder.BIG_ENDIAN));
        pos += 8;
        BigDecimal askQuantity = fromInt64(buffer.getLong(pos, ByteOrder.BIG_ENDIAN));
        pos += 8;

        // 24h stats
        BigDecimal volume24h = fromInt64(buffer.getLong(pos, ByteOrder.BIG_ENDIAN));
        pos += 8;
        BigDecimal change24h = fromInt64(buffer.getLong(pos, ByteOrder.BIG_ENDIAN));
        pos += 8;
        BigDecimal changePercent24h = fromInt64(buffer.getLong(pos, ByteOrder.BIG_ENDIAN));

        return new Ticker(
            exchange, symbol, timestamp, gatewayTimestamp,
            lastPrice, bidPrice, askPrice, bidQuantity, askQuantity,
            volume24h, change24h, changePercent24h
        );
    }

    // ==================== Trade Decoding ====================

    /**
     * Decodes a Trade from buffer.
     */
    public static Trade decodeTrade(DirectBuffer buffer, int offset) {
        int pos = offset;

        // Skip message type
        pos += 1;

        // Exchange
        Exchange exchange = Exchange.values()[buffer.getByte(pos)];
        pos += 1;

        // Symbol
        int symbolLen = buffer.getByte(pos) & 0xFF;
        pos += 1;
        String symbol = getString(buffer, pos, symbolLen);
        pos += symbolLen;

        // Timestamps
        long timestamp = buffer.getLong(pos, ByteOrder.BIG_ENDIAN);
        pos += 8;
        long gatewayTimestamp = buffer.getLong(pos, ByteOrder.BIG_ENDIAN);
        pos += 8;

        // Trade ID
        int tradeIdLen = buffer.getByte(pos) & 0xFF;
        pos += 1;
        String tradeId = getString(buffer, pos, tradeIdLen);
        pos += tradeIdLen;

        // Price and quantity
        BigDecimal price = fromInt64(buffer.getLong(pos, ByteOrder.BIG_ENDIAN));
        pos += 8;
        BigDecimal quantity = fromInt64(buffer.getLong(pos, ByteOrder.BIG_ENDIAN));
        pos += 8;

        // Side
        Side side = Side.values()[buffer.getByte(pos)];

        return new Trade(
            exchange, symbol, timestamp, gatewayTimestamp,
            tradeId, price, quantity, side
        );
    }

    // ==================== OrderBook Decoding ====================

    /**
     * Decodes an OrderBook from buffer.
     */
    public static OrderBook decodeOrderBook(DirectBuffer buffer, int offset) {
        int pos = offset;

        // Skip message type
        pos += 1;

        // Exchange
        Exchange exchange = Exchange.values()[buffer.getByte(pos)];
        pos += 1;

        // Symbol
        int symbolLen = buffer.getByte(pos) & 0xFF;
        pos += 1;
        String symbol = getString(buffer, pos, symbolLen);
        pos += symbolLen;

        // Timestamps
        long timestamp = buffer.getLong(pos, ByteOrder.BIG_ENDIAN);
        pos += 8;
        long gatewayTimestamp = buffer.getLong(pos, ByteOrder.BIG_ENDIAN);
        pos += 8;

        // Counts
        int numBids = buffer.getByte(pos) & 0xFF;
        pos += 1;
        int numAsks = buffer.getByte(pos) & 0xFF;
        pos += 1;

        // Snapshot flag
        boolean isSnapshot = buffer.getByte(pos) == 1;
        pos += 1;

        // Bids
        List<OrderBookLevel> bids = new ArrayList<>(numBids);
        for (int i = 0; i < numBids; i++) {
            BigDecimal price = fromInt64(buffer.getLong(pos, ByteOrder.BIG_ENDIAN));
            pos += 8;
            BigDecimal quantity = fromInt64(buffer.getLong(pos, ByteOrder.BIG_ENDIAN));
            pos += 8;
            bids.add(new OrderBookLevel(price, quantity));
        }

        // Asks
        List<OrderBookLevel> asks = new ArrayList<>(numAsks);
        for (int i = 0; i < numAsks; i++) {
            BigDecimal price = fromInt64(buffer.getLong(pos, ByteOrder.BIG_ENDIAN));
            pos += 8;
            BigDecimal quantity = fromInt64(buffer.getLong(pos, ByteOrder.BIG_ENDIAN));
            pos += 8;
            asks.add(new OrderBookLevel(price, quantity));
        }

        return new OrderBook(
            exchange, symbol, timestamp, gatewayTimestamp,
            bids, asks, isSnapshot
        );
    }

    // ==================== Universal Decoder ====================

    /**
     * Detects message type and decodes accordingly.
     */
    public static Object decode(DirectBuffer buffer, int offset) {
        byte msgType = getMessageType(buffer, offset);
        return switch (msgType) {
            case BinaryEncoder.MSG_TYPE_TICKER -> decodeTicker(buffer, offset);
            case BinaryEncoder.MSG_TYPE_TRADE -> decodeTrade(buffer, offset);
            case BinaryEncoder.MSG_TYPE_ORDER_BOOK -> decodeOrderBook(buffer, offset);
            default -> throw new IllegalArgumentException("Unknown message type: " + msgType);
        };
    }

    /**
     * Detects message type from byte array.
     */
    public static byte detectMessageType(byte[] data) {
        return data[0];
    }

    // ==================== Utility Methods ====================

    /**
     * Extracts a string from the buffer at the given position and length.
     */
    private static String getString(DirectBuffer buffer, int position, int length) {
        byte[] bytes = new byte[length];
        buffer.getBytes(position, bytes, 0, length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Decodes int64 back to BigDecimal.
     */
    public static BigDecimal fromInt64(long value) {
        return BigDecimal.valueOf(value, 8);
    }
}
