package io.trading.gateway.exchange.binance;

import io.trading.gateway.model.DataType;
import io.trading.gateway.model.Exchange;
import io.trading.gateway.model.OrderBook;
import io.trading.gateway.model.Side;
import io.trading.gateway.model.Ticker;
import io.trading.gateway.model.Trade;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BinanceMessageParser.
 * Tests parsing of real Binance WebSocket messages.
 */
class BinanceMessageParserTest {

    private final BinanceMessageParser parser = new BinanceMessageParser();

    @Test
    void testIsTicker() {
        String tickerMessage = "{\"e\":\"24hrTicker\",\"E\":1704067200000,\"s\":\"BTCUSDT\",\"c\":\"43250.50\"}";
        assertTrue(parser.isTicker(tickerMessage));

        String nonTickerMessage = "{\"e\":\"trade\",\"E\":1704067200000,\"s\":\"BTCUSDT\"}";
        assertFalse(parser.isTicker(nonTickerMessage));
    }

    @Test
    void testIsTrade() {
        String tradeMessage = "{\"e\":\"trade\",\"E\":1704067200000,\"s\":\"BTCUSDT\",\"t\":\"123456789\"}";
        assertTrue(parser.isTrade(tradeMessage));

        String nonTradeMessage = "{\"e\":\"24hrTicker\",\"E\":1704067200000,\"s\":\"BTCUSDT\"}";
        assertFalse(parser.isTrade(nonTradeMessage));
    }

    @Test
    void testIsOrderBook() {
        String orderBookMessage = "{\"e\":\"depthUpdate\",\"E\":1704067200000,\"s\":\"BTCUSDT\"}";
        assertTrue(parser.isOrderBook(orderBookMessage));

        String nonOrderBookMessage = "{\"e\":\"trade\",\"E\":1704067200000,\"s\":\"BTCUSDT\"}";
        assertFalse(parser.isOrderBook(nonOrderBookMessage));
    }

    @Test
    void testParseTicker() {
        // Real Binance ticker message
        String message = """
            {
                "e": "24hrTicker",
                "E": 1704067200000,
                "s": "BTCUSDT",
                "c": "43250.50",
                "b": "43250.00",
                "a": "43251.00",
                "B": "1.5",
                "A": "2.0",
                "v": "12345.67",
                "p": "250.50",
                "P": "0.58"
            }
            """;

        Ticker ticker = parser.parseTicker(message);

        assertNotNull(ticker);
        assertEquals(Exchange.BINANCE, ticker.exchange());
        assertEquals("BTCUSDT", ticker.symbol());
        assertEquals(1704067200000L, ticker.timestamp());
        assertEquals(0, new BigDecimal("43250.50").compareTo(ticker.lastPrice()));
        assertEquals(0, new BigDecimal("43250.00").compareTo(ticker.bidPrice()));
        assertEquals(0, new BigDecimal("43251.00").compareTo(ticker.askPrice()));
        assertEquals(0, new BigDecimal("1.5").compareTo(ticker.bidQuantity()));
        assertEquals(0, new BigDecimal("2.0").compareTo(ticker.askQuantity()));
        assertEquals(0, new BigDecimal("12345.67").compareTo(ticker.volume24h()));
        assertEquals(0, new BigDecimal("250.50").compareTo(ticker.change24h()));
        assertEquals(0, new BigDecimal("0.58").compareTo(ticker.changePercent24h()));
    }

    @Test
    void testParseTrade() {
        // Real Binance trade message - note: t is a number in real API
        String message = """
            {
                "e": "trade",
                "E": 1704067200000,
                "s": "BTCUSDT",
                "t": 123456789,
                "p": "43250.50",
                "q": "0.5",
                "m": true
            }
            """;

        Trade trade = parser.parseTrade(message);

        assertNotNull(trade);
        assertEquals(Exchange.BINANCE, trade.exchange());
        assertEquals("BTCUSDT", trade.symbol());
        assertEquals(1704067200000L, trade.timestamp());
        assertEquals("123456789", trade.tradeId());
        assertEquals(0, new BigDecimal("43250.50").compareTo(trade.price()));
        assertEquals(0, new BigDecimal("0.5").compareTo(trade.quantity()));
        assertEquals(Side.SELL, trade.side()); // m=true means buyer is maker, so it's a sell
    }

    @Test
    void testParseTradeBuy() {
        String message = """
            {
                "e": "trade",
                "E": 1704067200000,
                "s": "BTCUSDT",
                "t": 123456789,
                "p": "43250.50",
                "q": "0.5",
                "m": false
            }
            """;

        Trade trade = parser.parseTrade(message);
        assertEquals(Side.BUY, trade.side()); // m=false means buyer is not maker (aggressive buyer), so it's a buy
    }

    @Test
    void testParseOrderBook() {
        // Real Binance depth update message
        String message = """
            {
                "e": "depthUpdate",
                "E": 1704067200000,
                "s": "BTCUSDT",
                "b": [
                    ["43250.00", "1.5"],
                    ["43249.00", "2.0"]
                ],
                "a": [
                    ["43251.00", "2.0"],
                    ["43252.00", "1.0"]
                ]
            }
            """;

        OrderBook orderBook = parser.parseOrderBook(message);

        assertNotNull(orderBook);
        assertEquals(Exchange.BINANCE, orderBook.exchange());
        assertEquals("BTCUSDT", orderBook.symbol());
        assertEquals(1704067200000L, orderBook.timestamp());
        assertEquals(2, orderBook.bids().size());
        assertEquals(2, orderBook.asks().size());
        assertFalse(orderBook.isSnapshot()); // Binance depth updates are incremental

        // Check first bid
        assertEquals(0, new BigDecimal("43250.00").compareTo(orderBook.bids().get(0).price()));
        assertEquals(0, new BigDecimal("1.5").compareTo(orderBook.bids().get(0).quantity()));

        // Check first ask
        assertEquals(0, new BigDecimal("43251.00").compareTo(orderBook.asks().get(0).price()));
        assertEquals(0, new BigDecimal("2.0").compareTo(orderBook.asks().get(0).quantity()));
    }

    @Test
    void testParseTickerInvalidJson() {
        String invalidMessage = "{invalid json}";
        assertThrows(RuntimeException.class, () -> parser.parseTicker(invalidMessage));
    }
}
