package io.trading.gateway.aeron;

import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.trading.gateway.model.DataType;
import io.trading.gateway.model.Exchange;
import io.trading.gateway.model.OrderBook;
import io.trading.gateway.model.Ticker;
import io.trading.gateway.model.Trade;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance Aeron publisher for market data distribution.
 *
 * Performance optimizations:
 * 1. Direct UnsafeBuffer wrapping (zero-copy on Aeron side)
 * 2. SLF4J logging instead of System.out
 * 3. Optimized backpressure logging
 * 4. Publication caching
 */
public class AeronPublisher implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(AeronPublisher.class);
    private static final int BACKPRESSURE_LOG_INTERVAL = 1000;

    private final Aeron aeron;
    private final Map<PublicationKey, ExclusivePublication> publications;
    private final AtomicLong publishFailures = new AtomicLong(0);

    public AeronPublisher(Aeron aeron) {
        this.aeron = aeron;
        this.publications = new ConcurrentHashMap<>();
    }

    /**
     * Publishes a ticker message to the appropriate Aeron stream.
     */
    public boolean publishTicker(Ticker ticker) {
        return publish(ticker.exchange(), DataType.TICKER, ticker);
    }

    /**
     * Publishes a trade message to the appropriate Aeron stream.
     */
    public boolean publishTrade(Trade trade) {
        return publish(trade.exchange(), DataType.TRADES, trade);
    }

    /**
     * Publishes an order book message to the appropriate Aeron stream.
     */
    public boolean publishOrderBook(OrderBook orderBook) {
        return publish(orderBook.exchange(), DataType.ORDER_BOOK, orderBook);
    }

    /**
     * Internal publish method.
     */
    private boolean publish(Exchange exchange, DataType dataType, Object message) {
        try {
            ExclusivePublication publication = getOrCreatePublication(exchange, dataType);
            byte[] data = MessageEncoder.encode(message);

            UnsafeBuffer buffer = new UnsafeBuffer(data);
            long result = publication.offer(buffer, 0, data.length);

            if (result < 0) {
                handleBackpressure(result);
                return false;
            }

            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to encode message", e);
            return false;
        }
    }

    /**
     * Gets or creates an ExclusivePublication.
     * Synchronized for thread safety.
     */
    private ExclusivePublication getOrCreatePublication(Exchange exchange, DataType dataType) {
        PublicationKey key = new PublicationKey(exchange, dataType);
        return publications.computeIfAbsent(key, k -> {
            String channel = StreamRegistry.getChannel(k.exchange, k.dataType);
            int streamId = StreamRegistry.getStreamId(k.exchange, k.dataType);

            LOGGER.info("Creating Aeron publication: channel={}, streamId={}", channel, streamId);
            return aeron.addExclusivePublication(channel, streamId);
        });
    }

    /**
     * Handles backpressure with throttled logging.
     */
    private void handleBackpressure(long result) {
        long failures = publishFailures.incrementAndGet();

        if (failures % BACKPRESSURE_LOG_INTERVAL == 0) {
            LOGGER.warn("Aeron publication backpressure (count: {}, code: {})",
                failures, result);
        }
    }

    public long getPublishFailureCount() {
        return publishFailures.get();
    }

    public void resetFailureCount() {
        publishFailures.set(0);
    }

    public int getPublicationCount() {
        return publications.size();
    }

    @Override
    public void close() {
        publications.values().forEach(CloseHelper::quietClose);
        publications.clear();
    }

    private record PublicationKey(Exchange exchange, DataType dataType) {}
}
