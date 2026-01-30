package io.trading.gateway.core;

import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.trading.gateway.aeron.AeronPublisher;
import io.trading.gateway.config.ExchangeConfig;
import io.trading.gateway.config.GatewayConfig;
import io.trading.gateway.config.SymbolConfig;
import io.trading.gateway.exchange.ExchangeConnector;
import io.trading.gateway.exchange.ExchangeMessageHandler;
import io.trading.gateway.exchange.binance.BinanceConnector;
import io.trading.gateway.exchange.bybit.BybitConnector;
import io.trading.gateway.exchange.okx.OkxConnector;
import io.trading.gateway.metrics.GatewayMetrics;
import io.trading.gateway.metrics.MetricsServer;
import io.trading.gateway.model.DataType;
import io.trading.gateway.model.Exchange;
import io.trading.gateway.model.OrderBook;
import io.trading.gateway.model.Ticker;
import io.trading.gateway.model.Trade;
import org.agrona.CloseHelper;
import org.agrona.concurrent.ShutdownSignalBarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Main controller for the Trading Gateway.
 * Manages Aeron, exchange connectors, and message routing.
 */
public class GatewayController implements AutoCloseable, ExchangeMessageHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayController.class);

    private final GatewayConfig config;
    private final MediaDriver mediaDriver;
    private final Aeron aeron;
    private final AeronPublisher publisher;
    private final HealthMonitor healthMonitor;
    private final GatewayMetrics metrics;
    private final MetricsServer metricsServer;
    private final ProcessingTimer processingTimer;
    private final Map<Exchange, ExchangeConnector> connectors;
    private final ShutdownSignalBarrier shutdownBarrier;
    private final ScheduledExecutorService scheduler;

    public GatewayController(GatewayConfig config) {
        this.config = config;

        // Start embedded media driver
        String aeronDir = config.aeronDir();

        // Use temp directory if /dev/shm is not available (e.g., on macOS)
        if (!java.nio.file.Files.exists(java.nio.file.Paths.get("/dev/shm"))) {
            aeronDir = System.getProperty("java.io.tmpdir") + "/trading-gateway-" + config.gatewayId();
            LOGGER.info("Using temp directory for Aeron: {}", aeronDir);
        }

        // Ensure directory exists
        try {
            java.nio.file.Path dirPath = java.nio.file.Paths.get(aeronDir);
            if (!java.nio.file.Files.exists(dirPath)) {
                java.nio.file.Files.createDirectories(dirPath);
            }
        } catch (java.io.IOException e) {
            LOGGER.warn("Could not create Aeron directory: {}", aeronDir, e);
        }

        MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
            .aeronDirectoryName(aeronDir)
            .threadingMode(ThreadingMode.SHARED)
            .dirDeleteOnStart(true);

        this.mediaDriver = MediaDriver.launchEmbedded(mediaDriverContext);
        LOGGER.info("Media driver started: dir={}", aeronDir);

        // Configure Aeron context
        Aeron.Context context = new Aeron.Context()
            .aeronDirectoryName(mediaDriver.aeronDirectoryName())
            .useConductorAgentInvoker(true);

        this.aeron = Aeron.connect(context);
        this.metrics = new GatewayMetrics();
        this.publisher = new AeronPublisher(aeron, metrics);
        this.healthMonitor = new HealthMonitor(config.healthCheckMs());
        this.metricsServer = new MetricsServer(config.metricsPort(), metrics, config, healthMonitor);
        this.processingTimer = new ProcessingTimer();
        this.connectors = new HashMap<>();
        this.shutdownBarrier = new ShutdownSignalBarrier();
        this.scheduler = Executors.newScheduledThreadPool(2);

        LOGGER.info("Gateway controller initialized: {}", config.gatewayId());
    }

    /**
     * Starts the gateway and all exchange connectors.
     */
    public void start() throws java.io.IOException {
        LOGGER.info("Starting Trading Gateway...");

        // Start health monitor
        healthMonitor.start();

        // Start metrics server
        metricsServer.start();

        // Create and start exchange connectors
        for (ExchangeConfig exchangeConfig : config.exchangeConfigs()) {
            if (!exchangeConfig.enabled()) {
                LOGGER.info("Skipping disabled exchange: {}", exchangeConfig.exchange());
                continue;
            }

            ExchangeConnector connector = createConnector(exchangeConfig.exchange());
            if (connector != null) {
                connector.setMessageHandler(this);
                connector.connect();
                connectors.put(exchangeConfig.exchange(), connector);

                // Register with health monitor
                healthMonitor.registerExchange(
                    exchangeConfig.exchange(),
                    new HealthMonitor.ConnectionChecker() {
                        @Override
                        public boolean isConnected() {
                            return connector.isConnected();
                        }

                        @Override
                        public long getMessageCount() {
                            return connector.getMessageCount();
                        }

                        @Override
                        public long getErrorCount() {
                            return connector.getErrorCount();
                        }
                    }
                );

                // Schedule subscription retry every 2 seconds until connected
                scheduleSubscriptionRetry(connector, exchangeConfig.exchange());

                LOGGER.info("Started connector for {}", exchangeConfig.exchange());
            }
        }

        LOGGER.info("Trading Gateway started successfully");
        logStatus();
    }

    /**
     * Schedules periodic subscription retries until the connector is connected.
     */
    private void scheduleSubscriptionRetry(ExchangeConnector connector, Exchange exchange) {
        final java.util.concurrent.ScheduledFuture<?>[] futureHolder = new java.util.concurrent.ScheduledFuture<?>[1];

        Runnable subscribeTask = new Runnable() {
            private int attemptCount = 0;
            private final int MAX_ATTEMPTS = 3;

            @Override
            public void run() {
                if (connector.isConnected() && attemptCount < MAX_ATTEMPTS) {
                    attemptCount++;

                    Set<SymbolConfig> symbolConfigs = config.symbolConfigs().stream()
                        .filter(sc -> sc.exchanges().contains(exchange))
                        .collect(Collectors.toSet());

                    if (!symbolConfigs.isEmpty()) {
                        Set<DataType> dataTypes = config.exchangeConfigs().stream()
                            .filter(ec -> ec.exchange() == exchange)
                            .flatMap(ec -> ec.dataTypes().stream())
                            .collect(Collectors.toSet());

                        for (SymbolConfig symbolConfig : symbolConfigs) {
                            connector.subscribe(java.util.Set.of(symbolConfig.symbol()), dataTypes);
                            LOGGER.info("[{}] Subscribed to {} for data types: {} (attempt {}/{})",
                                exchange, symbolConfig.symbol(), dataTypes, attemptCount, MAX_ATTEMPTS);
                        }
                    }

                    // Cancel after successful subscription attempts
                    if (attemptCount >= MAX_ATTEMPTS && futureHolder[0] != null) {
                        futureHolder[0].cancel(false);
                        LOGGER.debug("[{}] Subscription scheduler cancelled after {} attempts", exchange, MAX_ATTEMPTS);
                    }
                }
            }
        };

        futureHolder[0] = scheduler.scheduleAtFixedRate(subscribeTask, 0, 2, TimeUnit.SECONDS);
    }

    /**
     * Waits for shutdown signal.
     */
    public void waitForShutdown() {
        LOGGER.info("Gateway running. Press Ctrl+C to shutdown.");
        shutdownBarrier.await();

        LOGGER.info("Shutdown signal received");
    }

    /**
     * Stops the gateway gracefully.
     */
    public void shutdown() {
        LOGGER.info("Shutting down Trading Gateway...");

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        CloseHelper.closeAll(publisher, healthMonitor, metricsServer);
        for (ExchangeConnector connector : connectors.values()) {
            try {
                connector.close();
            } catch (Exception e) {
                LOGGER.error("Error closing connector", e);
            }
        }
        connectors.clear();
        CloseHelper.close(aeron);
        CloseHelper.close(mediaDriver);

        LOGGER.info("Trading Gateway shutdown complete");
    }

    @Override
    public void close() {
        shutdown();
    }

    @Override
    public void onTicker(Ticker ticker) {
        LOGGER.info("[Gateway] onTicker called: exchange={}, symbol={}", ticker.exchange(), ticker.symbol());
        ProcessingTimer.TimingContext timer = processingTimer.start();

        metrics.recordMessageReceived(ticker.exchange(), DataType.TICKER);
        LOGGER.info("[Gateway] recordMessageReceived called for {}", ticker.exchange());

        // Get parse latency from connector and record to metrics
        ExchangeConnector connector = connectors.get(ticker.exchange());
        if (connector != null) {
            ProcessingTimer.TimerStats parseStats = connector.getProcessingTimer()
                .getStats(ticker.exchange().name(), "TICKER");
            if (parseStats != null && parseStats.getCount() > 0) {
                metrics.recordParseLatency(ticker.exchange(), DataType.TICKER, parseStats.getAvgMicros());
            }
        }

        publisher.publishTicker(ticker);
        metrics.recordMessagePublished(ticker.exchange(), DataType.TICKER);

        long totalMicros = timer.stopMicros();
        metrics.recordMessageLatency(ticker.exchange(), DataType.TICKER, totalMicros);
        LOGGER.debug("[Gateway] Ticker total latency: {} us", totalMicros);
    }

    @Override
    public void onTrade(Trade trade) {
        ProcessingTimer.TimingContext timer = processingTimer.start();

        metrics.recordMessageReceived(trade.exchange(), DataType.TRADES);

        // Get parse latency from connector and record to metrics
        ExchangeConnector connector = connectors.get(trade.exchange());
        if (connector != null) {
            ProcessingTimer.TimerStats parseStats = connector.getProcessingTimer()
                .getStats(trade.exchange().name(), "TRADE");
            if (parseStats != null && parseStats.getCount() > 0) {
                metrics.recordParseLatency(trade.exchange(), DataType.TRADES, parseStats.getAvgMicros());
            }
        }

        publisher.publishTrade(trade);
        metrics.recordMessagePublished(trade.exchange(), DataType.TRADES);

        long totalMicros = timer.stopMicros();
        metrics.recordMessageLatency(trade.exchange(), DataType.TRADES, totalMicros);
        LOGGER.debug("[Gateway] Trade total latency: {} us", totalMicros);
    }

    @Override
    public void onOrderBook(OrderBook orderBook) {
        ProcessingTimer.TimingContext timer = processingTimer.start();

        metrics.recordMessageReceived(orderBook.exchange(), DataType.ORDER_BOOK);

        // Get parse latency from connector and record to metrics
        ExchangeConnector connector = connectors.get(orderBook.exchange());
        if (connector != null) {
            ProcessingTimer.TimerStats parseStats = connector.getProcessingTimer()
                .getStats(orderBook.exchange().name(), "ORDER_BOOK");
            if (parseStats != null && parseStats.getCount() > 0) {
                metrics.recordParseLatency(orderBook.exchange(), DataType.ORDER_BOOK, parseStats.getAvgMicros());
            }
        }

        publisher.publishOrderBook(orderBook);
        metrics.recordMessagePublished(orderBook.exchange(), DataType.ORDER_BOOK);

        long totalMicros = timer.stopMicros();
        metrics.recordMessageLatency(orderBook.exchange(), DataType.ORDER_BOOK, totalMicros);
        LOGGER.debug("[Gateway] OrderBook total latency: {} us", totalMicros);
    }

    /**
     * Creates a connector for the given exchange.
     */
    private ExchangeConnector createConnector(Exchange exchange) {
        return switch (exchange) {
            case BINANCE -> new BinanceConnector();
            case OKX -> new OkxConnector();
            case BYBIT -> new BybitConnector();
        };
    }

    /**
     * Logs current gateway status.
     */
    public void logStatus() {
        LOGGER.info("=== Gateway Status ===");
        LOGGER.info("Gateway ID: {}", config.gatewayId());
        LOGGER.info("Aeron Dir: {}", config.aeronDir());
        LOGGER.info("Active Publications: {}", publisher.getPublicationCount());
        LOGGER.info("Publish Failures: {}", publisher.getPublishFailureCount());

        // Log processing latency stats for each connector
        for (Map.Entry<Exchange, ExchangeConnector> entry : connectors.entrySet()) {
            ExchangeConnector connector = entry.getValue();
            ProcessingTimer timer = connector.getProcessingTimer();
            LOGGER.info("{}: connected={}, messages={}, errors={}",
                entry.getKey(),
                connector.isConnected(),
                connector.getMessageCount(),
                connector.getErrorCount()
            );
            logProcessingStats(timer, entry.getKey().name());
        }

        // Log publish latency stats
        LOGGER.info("--- Publish Latency Stats ---");
        logProcessingStats(publisher.getProcessingTimer(), "PUBLISH");

        healthMonitor.logSummary();
        LOGGER.info("=====================");
    }

    /**
     * Logs processing statistics from a timer.
     */
    private void logProcessingStats(ProcessingTimer timer, String label) {
        for (Map.Entry<ProcessingTimer.TimerKey, ProcessingTimer.TimerStats> entry :
             timer.getAllStats().entrySet()) {
            ProcessingTimer.TimerStats stats = entry.getValue();
            if (stats.getCount() > 0) {
                LOGGER.info("  [{}] {}: count={}, avg={} us, min={} us, max={} us",
                    label,
                    entry.getKey().getDataType(),
                    stats.getCount(),
                    String.format("%.2f", stats.getAvgMicros()),
                    stats.getMinMicros(),
                    stats.getMaxMicros()
                );
            }
        }
    }

    /**
     * Gets the shutdown barrier for external signal handling.
     */
    public ShutdownSignalBarrier getShutdownBarrier() {
        return shutdownBarrier;
    }

    /**
     * Gets the Aeron instance.
     */
    public Aeron getAeron() {
        return aeron;
    }
}
