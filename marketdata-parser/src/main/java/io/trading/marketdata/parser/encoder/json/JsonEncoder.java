package io.trading.marketdata.parser.encoder.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.trading.marketdata.parser.encoder.MessageEncoder;
import io.trading.marketdata.parser.model.OrderBook;
import io.trading.marketdata.parser.model.Ticker;
import io.trading.marketdata.parser.model.Trade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON encoder using Jackson for high-performance JSON serialization.
 * Thread-safe and reusable.
 */
public class JsonEncoder implements MessageEncoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(JsonEncoder.class);

    private final ObjectMapper objectMapper;

    private static final JsonEncoder INSTANCE = new JsonEncoder();

    private JsonEncoder() {
        this.objectMapper = new ObjectMapper();
        // Configure for pretty printing if needed for debugging
        // objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        // Use ISO8601 date format if we had date fields
        // objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Gets the singleton instance.
     */
    public static JsonEncoder getInstance() {
        return INSTANCE;
    }

    @Override
    public byte[] encodeTickerToSbe(Ticker ticker) {
        throw new UnsupportedOperationException("SBE encoding not supported by JsonEncoder");
    }

    @Override
    public byte[] encodeTradeToSbe(Trade trade) {
        throw new UnsupportedOperationException("SBE encoding not supported by JsonEncoder");
    }

    @Override
    public byte[] encodeOrderBookToSbe(OrderBook orderBook) {
        throw new UnsupportedOperationException("SBE encoding not supported by JsonEncoder");
    }

    @Override
    public String encodeTickerToJson(Ticker ticker) {
        try {
            return objectMapper.writeValueAsString(ticker);
        } catch (JsonProcessingException e) {
            LOGGER.error("Failed to encode ticker to JSON: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to encode ticker to JSON", e);
        }
    }

    @Override
    public String encodeTradeToJson(Trade trade) {
        try {
            return objectMapper.writeValueAsString(trade);
        } catch (JsonProcessingException e) {
            LOGGER.error("Failed to encode trade to JSON: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to encode trade to JSON", e);
        }
    }

    @Override
    public String encodeOrderBookToJson(OrderBook orderBook) {
        try {
            return objectMapper.writeValueAsString(orderBook);
        } catch (JsonProcessingException e) {
            LOGGER.error("Failed to encode order book to JSON: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to encode order book to JSON", e);
        }
    }
}
