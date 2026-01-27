package io.trading.gateway.aeron;

import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.trading.gateway.model.DataType;
import io.trading.gateway.model.Exchange;
import io.trading.gateway.model.OrderBook;
import io.trading.gateway.model.Ticker;
import io.trading.gateway.model.Trade;
import org.agrona.CloseHelper;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance Aeron publisher for market data distribution using binary encoding.
 *
 * Performance optimizations:
 * 1. Binary encoding - fixed schema, zero reflection, minimal CPU cache misses
 * 2. Direct UnsafeBuffer wrapping (zero-copy on Aeron side)
 * 3. Reusable buffers for hot path (zero-allocation)
 * 4. Publication caching
 */
public class AeronPublisher implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(AeronPublisher.class);
    private static final int BACKPRESSURE_LOG_INTERVAL = 1000;

    private final Aeron aeron;
    private final Map<PublicationKey, ExclusivePublication> publications;
    private final AtomicLong publishFailures = new AtomicLong(0);

    // Reusable buffers for zero-allocation encoding
    private final AtomicBuffer tickerBuffer = BinaryEncoder.getTickerBuffer();
    private final AtomicBuffer tradeBuffer = BinaryEncoder.getTradeBuffer();
    private final AtomicBuffer orderBookBuffer = BinaryEncoder.getOrderBookBuffer();

    public AeronPublisher(Aeron aeron) {
        this.aeron = aeron;
        this.publications = new ConcurrentHashMap<>();
    }

    /**
     * Publishes a ticker message to the appropriate Aeron stream.
     */
    public boolean publishTicker(Ticker ticker) {
        try {
            ExclusivePublication publication = getOrCreatePublication(ticker.exchange(), DataType.TICKER);
            int length = BinaryEncoder.encodeTicker(tickerBuffer, ticker);

            UnsafeBuffer buffer = new UnsafeBuffer(tickerBuffer, 0, length);
            long result = publication.offer(buffer, 0, length);

            if (result < 0) {
                handleBackpressure(result);
                return false;
            }

            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to encode/publish ticker", e);
            return false;
        }
    }

    /**
     * Publishes a trade message to the appropriate Aeron stream.
     */
    public boolean publishTrade(Trade trade) {
        try {
            ExclusivePublication publication = getOrCreatePublication(trade.exchange(), DataType.TRADES);
            int length = BinaryEncoder.encodeTrade(tradeBuffer, trade);

            UnsafeBuffer buffer = new UnsafeBuffer(tradeBuffer, 0, length);
            long result = publication.offer(buffer, 0, length);

            if (result < 0) {
                handleBackpressure(result);
                return false;
            }

            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to encode/publish trade", e);
            return false;
        }
    }

    /**
     * Publishes an order book message to the appropriate Aeron stream.
     */
    public boolean publishOrderBook(OrderBook orderBook) {
        try {
            ExclusivePublication publication = getOrCreatePublication(orderBook.exchange(), DataType.ORDER_BOOK);
            int length = BinaryEncoder.encodeOrderBook(orderBookBuffer, orderBook);

            UnsafeBuffer buffer = new UnsafeBuffer(orderBookBuffer, 0, length);
            long result = publication.offer(buffer, 0, length);

            if (result < 0) {
                handleBackpressure(result);
                return false;
            }

            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to encode/publish orderBook", e);
            return false;
        }
    }

    /**
     * Gets or creates an ExclusivePublication.
     * Thread-safe via ConcurrentHashMap.computeIfAbsent.
     */
    private ExclusivePublication getOrCreatePublication(Exchange exchange, DataType dataType) {
        PublicationKey key = new PublicationKey(exchange, dataType);
        return publications.computeIfAbsent(key, k -> {
            String channel = StreamRegistry.getChannel(k.exchange, k.dataType);
            int streamId = StreamRegistry.getStreamId(k.exchange, k.dataType);

            LOGGER.info("Creating Aeron publication: channel={}, streamId={}, encoding=binary", channel, streamId);
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
