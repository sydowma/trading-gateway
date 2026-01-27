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
        this.publisher = new AeronPublisher(aeron);
        this.healthMonitor = new HealthMonitor(config.healthCheckMs());
        this.metrics = new GatewayMetrics();
        this.metricsServer = new MetricsServer(config.metricsPort(), metrics, config, healthMonitor);
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
        scheduler.scheduleAtFixedRate(() -> {
            if (connector.isConnected()) {
                // Already connected, try subscriptions and cancel if successful
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
                        LOGGER.info("[{}] Subscribed to {} for data types: {}",
                            exchange, symbolConfig.symbol(), dataTypes);
                    }
                }
            }
        }, 0, 2, TimeUnit.SECONDS);
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
        metrics.recordMessageReceived(ticker.exchange(), DataType.TICKER);
        metrics.recordMessagePublished(ticker.exchange(), DataType.TICKER);
        publisher.publishTicker(ticker);
    }

    @Override
    public void onTrade(Trade trade) {
        metrics.recordMessageReceived(trade.exchange(), DataType.TRADES);
        metrics.recordMessagePublished(trade.exchange(), DataType.TRADES);
        publisher.publishTrade(trade);
    }

    @Override
    public void onOrderBook(OrderBook orderBook) {
        metrics.recordMessageReceived(orderBook.exchange(), DataType.ORDER_BOOK);
        metrics.recordMessagePublished(orderBook.exchange(), DataType.ORDER_BOOK);
        publisher.publishOrderBook(orderBook);
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

        for (Map.Entry<Exchange, ExchangeConnector> entry : connectors.entrySet()) {
            ExchangeConnector connector = entry.getValue();
            LOGGER.info("{}: connected={}, messages={}, errors={}",
                entry.getKey(),
                connector.isConnected(),
                connector.getMessageCount(),
                connector.getErrorCount()
            );
        }

        healthMonitor.logSummary();
        LOGGER.info("=====================");
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
