package io.trading.gateway.exchange.bybit;

import io.trading.gateway.model.Exchange;
import io.trading.gateway.model.OrderBook;
import io.trading.gateway.model.Side;
import io.trading.gateway.model.Ticker;
import io.trading.gateway.model.Trade;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BybitMessageParser.
 * Tests parsing of real Bybit WebSocket messages.
 */
class BybitMessageParserTest {

    private final BybitMessageParser parser = new BybitMessageParser();

    @Test
    void testIsTicker() {
        String tickerMessage = "{\"topic\":\"tickers.BTCUSDT\"}";
        assertTrue(parser.isTicker(tickerMessage));

        String nonTickerMessage = "{\"topic\":\"publicTrade.BTCUSDT\"}";
        assertFalse(parser.isTicker(nonTickerMessage));
    }

    @Test
    void testIsTrade() {
        String tradeMessage = "{\"topic\":\"publicTrade.BTCUSDT\"}";
        assertTrue(parser.isTrade(tradeMessage));

        String nonTradeMessage = "{\"topic\":\"tickers.BTCUSDT\"}";
        assertFalse(parser.isTrade(nonTradeMessage));
    }

    @Test
    void testIsOrderBook() {
        String orderBookMessage = "{\"topic\":\"orderbook.1.BTCUSDT\"}";
        assertTrue(parser.isOrderBook(orderBookMessage));

        String nonOrderBookMessage = "{\"topic\":\"publicTrade.BTCUSDT\"}";
        assertFalse(parser.isOrderBook(nonOrderBookMessage));
    }

    @Test
    void testParseTicker() {
        // Real Bybit ticker message
        String message = """
            {
                "topic": "tickers.BTCUSDT",
                "type": "snapshot",
                "ts": 1704067200000,
                "data": {
                    "s": "BTCUSDT",
                    "c": "43250.50",
                    "b1": "43250.00",
                    "a1": "43251.00",
                    "b1sz": "1.5",
                    "a1sz": "2.0",
                    "v": "12345.67",
                    "o": "43000.00",
                    "change24h": "250.50",
                    "t": 1704067200000
                }
            }
            """;

        Ticker ticker = parser.parseTicker(message);

        assertNotNull(ticker);
        assertEquals(Exchange.BYBIT, ticker.exchange());
        assertEquals("BTCUSDT", ticker.symbol());
        assertEquals(1704067200000L, ticker.timestamp());
        assertEquals(new BigDecimal("43250.50"), ticker.lastPrice());
        assertEquals(new BigDecimal("43250.00"), ticker.bidPrice());
        assertEquals(new BigDecimal("43251.00"), ticker.askPrice());
        assertEquals(new BigDecimal("1.5"), ticker.bidQuantity());
        assertEquals(new BigDecimal("2.0"), ticker.askQuantity());
        assertEquals(new BigDecimal("12345.67"), ticker.volume24h());
        assertEquals(new BigDecimal("250.50"), ticker.change24h());
        // changePercent24h is calculated from (last - open) / open * 100
        assertEquals(0, new BigDecimal("0.58").compareTo(ticker.changePercent24h()));
    }

    @Test
    void testParseTrade() {
        // Real Bybit trade message
        String message = """
            {
                "topic": "publicTrade.BTCUSDT",
                "type": "snapshot",
                "ts": 1704067200000,
                "data": [
                    {
                        "T": 1704067200000,
                        "s": "BTCUSDT",
                        "S": "Buy",
                        "p": "43250.50",
                        "v": "0.5",
                        "i": "123456789"
                    }
                ]
            }
            """;

        Trade trade = parser.parseTrade(message);

        assertNotNull(trade);
        assertEquals(Exchange.BYBIT, trade.exchange());
        assertEquals("BTCUSDT", trade.symbol());
        assertEquals(1704067200000L, trade.timestamp());
        assertEquals("123456789", trade.tradeId());
        assertEquals(new BigDecimal("43250.50"), trade.price());
        assertEquals(new BigDecimal("0.5"), trade.quantity());
        assertEquals(Side.BUY, trade.side());
    }

    @Test
    void testParseTradeSell() {
        String message = """
            {
                "topic": "publicTrade.BTCUSDT",
                "type": "snapshot",
                "ts": 1704067200000,
                "data": [
                    {
                        "T": 1704067200000,
                        "s": "BTCUSDT",
                        "S": "Sell",
                        "p": "43250.50",
                        "v": "0.5",
                        "i": "123456789"
                    }
                ]
            }
            """;

        Trade trade = parser.parseTrade(message);
        assertEquals(Side.SELL, trade.side());
    }

    @Test
    void testParseOrderBook() {
        // Real Bybit order book message
        String message = """
            {
                "topic": "orderbook.1.BTCUSDT",
                "type": "snapshot",
                "ts": 1704067200000,
                "data": {
                    "s": "BTCUSDT",
                    "b": [
                        ["43250.00", "1.5"],
                        ["43249.00", "2.0"]
                    ],
                    "a": [
                        ["43251.00", "2.0"],
                        ["43252.00", "1.0"]
                    ],
                    "u": 123456
                }
            }
            """;

        OrderBook orderBook = parser.parseOrderBook(message);

        assertNotNull(orderBook);
        assertEquals(Exchange.BYBIT, orderBook.exchange());
        assertEquals("BTCUSDT", orderBook.symbol());
        assertEquals(1704067200000L, orderBook.timestamp());
        assertEquals(2, orderBook.bids().size());
        assertEquals(2, orderBook.asks().size());

        // Check first bid
        assertEquals(new BigDecimal("43250.00"), orderBook.bids().get(0).price());
        assertEquals(new BigDecimal("1.5"), orderBook.bids().get(0).quantity());

        // Check first ask
        assertEquals(new BigDecimal("43251.00"), orderBook.asks().get(0).price());
        assertEquals(new BigDecimal("2.0"), orderBook.asks().get(0).quantity());
    }

    @Test
    void testParseTickerInvalidJson() {
        String invalidMessage = "{invalid json}";
        assertThrows(RuntimeException.class, () -> parser.parseTicker(invalidMessage));
    }
}
