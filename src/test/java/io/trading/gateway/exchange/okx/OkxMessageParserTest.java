package io.trading.gateway.exchange.okx;

import io.trading.gateway.model.Exchange;
import io.trading.gateway.model.OrderBook;
import io.trading.gateway.model.Side;
import io.trading.gateway.model.Ticker;
import io.trading.gateway.model.Trade;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OkxMessageParser.
 * Tests parsing of real OKX WebSocket messages.
 */
class OkxMessageParserTest {

    private final OkxMessageParser parser = new OkxMessageParser();

    @Test
    void testIsTicker() {
        String tickerMessage = "{\"arg\":{\"channel\":\"tickers\"},\"data\":[{}]}";
        assertTrue(parser.isTicker(tickerMessage));

        String nonTickerMessage = "{\"arg\":{\"channel\":\"trades\"},\"data\":[{}]}";
        assertFalse(parser.isTicker(nonTickerMessage));
    }

    @Test
    void testIsTrade() {
        String tradeMessage = "{\"arg\":{\"channel\":\"trades\"},\"data\":[{}]}";
        assertTrue(parser.isTrade(tradeMessage));

        String nonTradeMessage = "{\"arg\":{\"channel\":\"tickers\"},\"data\":[{}]}";
        assertFalse(parser.isTrade(nonTradeMessage));
    }

    @Test
    void testIsOrderBook() {
        String orderBookMessage = "{\"arg\":{\"channel\":\"books\"},\"data\":[{}]}";
        assertTrue(parser.isOrderBook(orderBookMessage));

        String nonOrderBookMessage = "{\"arg\":{\"channel\":\"trades\"},\"data\":[{}]}";
        assertFalse(parser.isOrderBook(nonOrderBookMessage));
    }

    @Test
    void testParseTicker() {
        // Real OKX ticker message
        String message = """
            {
                "arg": {
                    "channel": "tickers",
                    "instId": "BTC-USDT"
                },
                "data": [{
                    "instId": "BTC-USDT",
                    "last": "43250.50",
                    "lastSz": "0.5",
                    "askPx": "43251.00",
                    "bidPx": "43250.00",
                    "askSz": "2.0",
                    "bidSz": "1.5",
                    "vol24h": "12345.67",
                    "ts": "1704067200000",
                    "changeUtc8": "0.58"
                }]
            }
            """;

        Ticker ticker = parser.parseTicker(message);

        assertNotNull(ticker);
        assertEquals(Exchange.OKX, ticker.exchange());
        assertEquals("BTCUSDT", ticker.symbol()); // Converted from BTC-USDT to BTCUSDT
        assertEquals(1704067200000L, ticker.timestamp());
        assertEquals(new BigDecimal("43250.50"), ticker.lastPrice());
        assertEquals(new BigDecimal("43250.00"), ticker.bidPrice());
        assertEquals(new BigDecimal("43251.00"), ticker.askPrice());
        assertEquals(new BigDecimal("1.5"), ticker.bidQuantity());
        assertEquals(new BigDecimal("2.0"), ticker.askQuantity());
        assertEquals(new BigDecimal("12345.67"), ticker.volume24h());
        assertEquals(new BigDecimal("0.58"), ticker.changePercent24h());
    }

    @Test
    void testParseTrade() {
        // Real OKX trade message
        String message = """
            {
                "arg": {
                    "channel": "trades",
                    "instId": "BTC-USDT"
                },
                "data": [{
                    "instId": "BTC-USDT",
                    "tradeId": "123456789",
                    "px": "43250.50",
                    "sz": "0.5",
                    "side": "buy",
                    "ts": "1704067200000"
                }]
            }
            """;

        Trade trade = parser.parseTrade(message);

        assertNotNull(trade);
        assertEquals(Exchange.OKX, trade.exchange());
        assertEquals("BTCUSDT", trade.symbol()); // Converted from BTC-USDT
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
                "arg": {
                    "channel": "trades",
                    "instId": "BTC-USDT"
                },
                "data": [{
                    "instId": "BTC-USDT",
                    "tradeId": "123456789",
                    "px": "43250.50",
                    "sz": "0.5",
                    "side": "sell",
                    "ts": "1704067200000"
                }]
            }
            """;

        Trade trade = parser.parseTrade(message);
        assertEquals(Side.SELL, trade.side());
    }

    @Test
    void testParseOrderBook() {
        // Real OKX order book message
        String message = """
            {
                "arg": {
                    "channel": "books",
                    "instId": "BTC-USDT"
                },
                "data": [{
                    "instId": "BTC-USDT",
                    "bids": [
                        ["43250.00", "1.5", "0", "1"],
                        ["43249.00", "2.0", "0", "2"]
                    ],
                    "asks": [
                        ["43251.00", "2.0", "0", "1"],
                        ["43252.00", "1.0", "0", "2"]
                    ],
                    "ts": "1704067200000",
                    "action": "snapshot"
                }]
            }
            """;

        OrderBook orderBook = parser.parseOrderBook(message);

        assertNotNull(orderBook);
        assertEquals(Exchange.OKX, orderBook.exchange());
        assertEquals("BTCUSDT", orderBook.symbol());
        assertEquals(1704067200000L, orderBook.timestamp());
        assertEquals(2, orderBook.bids().size());
        assertEquals(2, orderBook.asks().size());
        assertTrue(orderBook.isSnapshot()); // action=snapshot

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
