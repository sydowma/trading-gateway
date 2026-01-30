package io.trading.gateway.exchange.binance;

import io.trading.marketdata.parser.model.DataType;
import io.trading.marketdata.parser.model.Exchange;
import io.trading.marketdata.parser.model.Trade;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FastBinanceParser.
 */
class FastBinanceParserTest {

    private final FastBinanceParser parser = new FastBinanceParser();

    @Test
    void testParseMessageTypeTrade() {
        String message = "{\"e\":\"trade\",\"E\":1704067200000,\"s\":\"BTCUSDT\"}";
        assertEquals(DataType.TRADES, parser.parseMessageType(message));
    }

    @Test
    void testParseMessageTypeTicker() {
        String message = "{\"e\":\"24hrTicker\",\"E\":1704067200000,\"s\":\"BTCUSDT\"}";
        assertEquals(DataType.TICKER, parser.parseMessageType(message));
    }

    @Test
    void testParseMessageTypeOrderBook() {
        String message = "{\"e\":\"depthUpdate\",\"E\":1704067200000,\"s\":\"BTCUSDT\"}";
        assertEquals(DataType.ORDER_BOOK, parser.parseMessageType(message));
    }

    @Test
    void testParseTrade() {
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
        assertEquals(io.trading.gateway.model.Side.SELL, trade.side());
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
        assertEquals(io.trading.gateway.model.Side.BUY, trade.side());
    }
}
