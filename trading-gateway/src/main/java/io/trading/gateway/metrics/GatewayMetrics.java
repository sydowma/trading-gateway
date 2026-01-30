package io.trading.gateway.metrics;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;
import io.prometheus.client.Summary;
import io.prometheus.client.hotspot.DefaultExports;
import io.trading.marketdata.parser.model.DataType;
import io.trading.marketdata.parser.model.Exchange;

/**
 * Prometheus metrics collector for the Trading Gateway.
 *
 * Tracks:
 * - Message counts per exchange and data type
 * - Connection status per exchange
 * - Error counts
 * - Message processing latency
 * - Publication backpressure events
 */
public class GatewayMetrics {

    // Counters
    private final Counter messagesReceived;
    private final Counter messagesPublished;
    private final Counter parseErrors;
    private final Counter connectionErrors;
    private final Counter reconnectAttempts;
    private final Counter publicationFailures;

    // Gauges
    private final Gauge connectionStatus;
    private final Gauge activeSubscriptions;

    // Summary (latency tracking in microseconds)
    private final Summary messageLatencyMicros;
    private final Summary parseLatencyMicros;
    private final Summary publishLatencyMicros;

    // Histogram (message size distribution)
    private final Histogram messageSize;

    public GatewayMetrics() {
        // Initialize default JVM metrics (GC, memory, threads, etc.)
        DefaultExports.initialize();

        // Message counters
        this.messagesReceived = Counter.build()
            .name("gateway_messages_received_total")
            .help("Total number of messages received from exchanges")
            .labelNames("exchange", "data_type")
            .register();

        this.messagesPublished = Counter.build()
            .name("gateway_messages_published_total")
            .help("Total number of messages published to Aeron")
            .labelNames("exchange", "data_type")
            .register();

        // Error counters
        this.parseErrors = Counter.build()
            .name("gateway_parse_errors_total")
            .help("Total number of message parsing errors")
            .labelNames("exchange")
            .register();

        this.connectionErrors = Counter.build()
            .name("gateway_connection_errors_total")
            .help("Total number of connection errors")
            .labelNames("exchange")
            .register();

        this.reconnectAttempts = Counter.build()
            .name("gateway_reconnect_attempts_total")
            .help("Total number of reconnection attempts")
            .labelNames("exchange")
            .register();

        this.publicationFailures = Counter.build()
            .name("gateway_publication_failures_total")
            .help("Total number of Aeron publication failures")
            .register();

        // Connection status gauge (1 = connected, 0 = disconnected)
        this.connectionStatus = Gauge.build()
            .name("gateway_connection_status")
            .help("Connection status to exchanges (1 = connected, 0 = disconnected)")
            .labelNames("exchange")
            .register();

        // Active subscriptions gauge
        this.activeSubscriptions = Gauge.build()
            .name("gateway_active_subscriptions")
            .help("Number of active symbol subscriptions")
            .labelNames("exchange")
            .register();

        // Message latency summary (in microseconds)
        this.messageLatencyMicros = Summary.build()
            .name("gateway_message_latency_microseconds")
            .help("Total message processing latency in microseconds (receive to publish)")
            .labelNames("exchange", "data_type")
            .register();

        // Parse latency summary (in microseconds)
        this.parseLatencyMicros = Summary.build()
            .name("gateway_parse_latency_microseconds")
            .help("Message parsing latency in microseconds")
            .labelNames("exchange", "data_type")
            .register();

        // Publish latency summary (in microseconds)
        this.publishLatencyMicros = Summary.build()
            .name("gateway_publish_latency_microseconds")
            .help("Message publish (encode + offer) latency in microseconds")
            .labelNames("exchange", "data_type")
            .register();

        // Message size histogram (in bytes)
        this.messageSize = Histogram.build()
            .name("gateway_message_size_bytes")
            .help("Message size distribution in bytes")
            .labelNames("exchange", "data_type")
            .buckets(100, 500, 1000, 5000, 10000)
            .register();
    }

    /**
     * Records a message received from an exchange.
     */
    public void recordMessageReceived(Exchange exchange, DataType dataType) {
        messagesReceived.labels(exchange.name(), dataType.name()).inc();
    }

    /**
     * Records a message published to Aeron.
     */
    public void recordMessagePublished(Exchange exchange, DataType dataType) {
        messagesPublished.labels(exchange.name(), dataType.name()).inc();
    }

    /**
     * Records a parsing error.
     */
    public void recordParseError(Exchange exchange) {
        parseErrors.labels(exchange.name()).inc();
    }

    /**
     * Records a connection error.
     */
    public void recordConnectionError(Exchange exchange) {
        connectionErrors.labels(exchange.name()).inc();
    }

    /**
     * Records a reconnection attempt.
     */
    public void recordReconnectAttempt(Exchange exchange) {
        reconnectAttempts.labels(exchange.name()).inc();
    }

    /**
     * Records a publication failure.
     */
    public void recordPublicationFailure() {
        publicationFailures.inc();
    }

    /**
     * Sets the connection status for an exchange.
     *
     * @param exchange The exchange
     * @param connected true if connected, false otherwise
     */
    public void setConnectionStatus(Exchange exchange, boolean connected) {
        connectionStatus.labels(exchange.name()).set(connected ? 1 : 0);
    }

    /**
     * Sets the number of active subscriptions for an exchange.
     */
    public void setActiveSubscriptions(Exchange exchange, int count) {
        activeSubscriptions.labels(exchange.name()).set(count);
    }

    /**
     * Records total message processing latency (receive to publish).
     *
     * @param exchange The exchange
     * @param dataType The data type
     * @param latencyMicros Latency in microseconds
     */
    public void recordMessageLatency(Exchange exchange, DataType dataType, double latencyMicros) {
        messageLatencyMicros.labels(exchange.name(), dataType.name()).observe(latencyMicros);
    }

    /**
     * Records message parsing latency.
     *
     * @param exchange The exchange
     * @param dataType The data type
     * @param latencyMicros Latency in microseconds
     */
    public void recordParseLatency(Exchange exchange, DataType dataType, double latencyMicros) {
        parseLatencyMicros.labels(exchange.name(), dataType.name()).observe(latencyMicros);
    }

    /**
     * Records message publish latency (encode + offer).
     *
     * @param exchange The exchange
     * @param dataType The data type
     * @param latencyMicros Latency in microseconds
     */
    public void recordPublishLatency(Exchange exchange, DataType dataType, double latencyMicros) {
        publishLatencyMicros.labels(exchange.name(), dataType.name()).observe(latencyMicros);
    }

    /**
     * Records message size.
     *
     * @param exchange The exchange
     * @param dataType The data type
     * @param sizeBytes Size in bytes
     */
    public void recordMessageSize(Exchange exchange, DataType dataType, double sizeBytes) {
        messageSize.labels(exchange.name(), dataType.name()).observe(sizeBytes);
    }

    /**
     * Returns the CollectorRegistry for HTTP server.
     */
    public CollectorRegistry getRegistry() {
        return CollectorRegistry.defaultRegistry;
    }

    /**
     * Gets the total message count for an exchange and data type.
     */
    public double getMessagesReceived(Exchange exchange, DataType dataType) {
        return messagesReceived.labels(exchange.name(), dataType.name()).get();
    }

    /**
     * Gets the total publication count for an exchange and data type.
     */
    public double getMessagesPublished(Exchange exchange, DataType dataType) {
        return messagesPublished.labels(exchange.name(), dataType.name()).get();
    }

    /**
     * Resets all metrics (useful for testing).
     */
    public void reset() {
        CollectorRegistry.defaultRegistry.clear();
        DefaultExports.initialize();
    }
}
