package io.trading.gateway.aeron;

import io.trading.gateway.model.Exchange;
import io.trading.gateway.model.OrderBook;
import io.trading.gateway.model.OrderBookLevel;
import io.trading.gateway.model.Side;
import io.trading.gateway.model.Ticker;
import io.trading.gateway.model.Trade;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/**
 * High-performance message encoder for Aeron publication.
 *
 * Uses StringBuilder for efficient JSON construction.
 * Avoids ObjectMapper overhead by directly building JSON strings.
 * Uses BigDecimal for precise financial data representation.
 */
public class MessageEncoder {

    private MessageEncoder() {
        // Utility class
    }

    /**
     * Encodes a ticker message to JSON bytes.
     */
    public static byte[] encodeTicker(Ticker ticker) throws IOException {
        return buildString(
            ticker.exchange(),
            ticker.symbol(),
            ticker.timestamp(),
            ticker.gatewayTimestamp(),
            ticker.lastPrice(),
            ticker.bidPrice(),
            ticker.askPrice(),
            ticker.bidQuantity(),
            ticker.askQuantity(),
            ticker.volume24h(),
            ticker.change24h(),
            ticker.changePercent24h()
        ).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Encodes a trade message to JSON bytes.
     */
    public static byte[] encodeTrade(Trade trade) throws IOException {
        return buildString(
            trade.exchange(),
            trade.symbol(),
            trade.timestamp(),
            trade.gatewayTimestamp(),
            trade.tradeId(),
            trade.price(),
            trade.quantity(),
            trade.side()
        ).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Encodes an order book message to JSON bytes.
     */
    public static byte[] encodeOrderBook(OrderBook orderBook) throws IOException {
        return buildString(orderBook).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Encodes any object to JSON bytes (fallback using ObjectMapper).
     */
    public static byte[] encode(Object obj) throws IOException {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(obj);
    }

    // Ticker encoding
    private static String buildString(
        Exchange exchange, String symbol, long timestamp, long gatewayTimestamp,
        BigDecimal lastPrice, BigDecimal bidPrice, BigDecimal askPrice,
        BigDecimal bidQuantity, BigDecimal askQuantity, BigDecimal volume24h,
        BigDecimal change24h, BigDecimal changePercent24h
    ) {
        return new StringBuilder(512)
            .append('{')
            .append("\"exchange\":\"").append(exchange).append('"')
            .append(",\"symbol\":\"").append(symbol).append('"')
            .append(",\"timestamp\":").append(timestamp)
            .append(",\"gatewayTimestamp\":").append(gatewayTimestamp)
            .append(",\"lastPrice\":").append(lastPrice)
            .append(",\"bidPrice\":").append(bidPrice)
            .append(",\"askPrice\":").append(askPrice)
            .append(",\"bidQuantity\":").append(bidQuantity)
            .append(",\"askQuantity\":").append(askQuantity)
            .append(",\"volume24h\":").append(volume24h)
            .append(",\"change24h\":").append(change24h)
            .append(",\"changePercent24h\":").append(changePercent24h)
            .append('}')
            .toString();
    }

    // Trade encoding
    private static String buildString(
        Exchange exchange, String symbol, long timestamp, long gatewayTimestamp,
        String tradeId, BigDecimal price, BigDecimal quantity, Side side
    ) {
        return new StringBuilder(256)
            .append('{')
            .append("\"exchange\":\"").append(exchange).append('"')
            .append(",\"symbol\":\"").append(symbol).append('"')
            .append(",\"timestamp\":").append(timestamp)
            .append(",\"gatewayTimestamp\":").append(gatewayTimestamp)
            .append(",\"tradeId\":\"").append(tradeId).append('"')
            .append(",\"price\":").append(price)
            .append(",\"quantity\":").append(quantity)
            .append(",\"side\":\"").append(side).append('"')
            .append('}')
            .toString();
    }

    // OrderBook encoding
    private static String buildString(OrderBook orderBook) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append('{');
        sb.append("\"exchange\":\"").append(orderBook.exchange()).append('"');
        sb.append(",\"symbol\":\"").append(orderBook.symbol()).append('"');
        sb.append(",\"timestamp\":").append(orderBook.timestamp());
        sb.append(",\"gatewayTimestamp\":").append(orderBook.gatewayTimestamp());
        sb.append(",\"bids\":[");
        for (int i = 0; i < orderBook.bids().size(); i++) {
            if (i > 0) sb.append(',');
            OrderBookLevel level = orderBook.bids().get(i);
            sb.append("{\"price\":").append(level.price())
              .append(",\"quantity\":").append(level.quantity()).append('}');
        }
        sb.append("],\"asks\":[");
        for (int i = 0; i < orderBook.asks().size(); i++) {
            if (i > 0) sb.append(',');
            OrderBookLevel level = orderBook.asks().get(i);
            sb.append("{\"price\":").append(level.price())
              .append(",\"quantity\":").append(level.quantity()).append('}');
        }
        sb.append("],\"isSnapshot\":").append(orderBook.isSnapshot());
        sb.append('}');
        return sb.toString();
    }
}
