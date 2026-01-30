package io.trading.marketdata.parser.encoder.java;

import io.trading.marketdata.parser.encoder.MessageEncoder;
import io.trading.marketdata.parser.model.OrderBook;
import io.trading.marketdata.parser.model.Ticker;
import io.trading.marketdata.parser.model.Trade;

/**
 * Java object encoder - pass-through implementation.
 * This encoder simply returns the Java objects as-is, providing zero overhead.
 * Used when the output format is JAVA (the default and fastest option).
 */
public class JavaObjectEncoder implements MessageEncoder {

    private static final JavaObjectEncoder INSTANCE = new JavaObjectEncoder();

    private JavaObjectEncoder() {
        // Private constructor for singleton
    }

    /**
     * Gets the singleton instance.
     */
    public static JavaObjectEncoder getInstance() {
        return INSTANCE;
    }

    @Override
    public byte[] encodeTickerToSbe(Ticker ticker) {
        // Not supported for Java object encoder
        throw new UnsupportedOperationException("SBE encoding not supported by JavaObjectEncoder");
    }

    @Override
    public byte[] encodeTradeToSbe(Trade trade) {
        // Not supported for Java object encoder
        throw new UnsupportedOperationException("SBE encoding not supported by JavaObjectEncoder");
    }

    @Override
    public byte[] encodeOrderBookToSbe(OrderBook orderBook) {
        // Not supported for Java object encoder
        throw new UnsupportedOperationException("SBE encoding not supported by JavaObjectEncoder");
    }

    @Override
    public String encodeTickerToJson(Ticker ticker) {
        // Not supported for Java object encoder
        throw new UnsupportedOperationException("JSON encoding not supported by JavaObjectEncoder");
    }

    @Override
    public String encodeTradeToJson(Trade trade) {
        // Not supported for Java object encoder
        throw new UnsupportedOperationException("JSON encoding not supported by JavaObjectEncoder");
    }

    @Override
    public String encodeOrderBookToJson(OrderBook orderBook) {
        // Not supported for Java object encoder
        throw new UnsupportedOperationException("JSON encoding not supported by JavaObjectEncoder");
    }
}
