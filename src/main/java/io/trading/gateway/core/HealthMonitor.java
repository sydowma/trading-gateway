package io.trading.gateway.core;

import io.trading.gateway.model.Exchange;
import io.trading.gateway.model.DataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Monitors the health of exchange connections and reports statistics.
 */
public class HealthMonitor implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(HealthMonitor.class);

    private final long checkIntervalMs;
    private final ScheduledExecutorService scheduler;
    private final Map<Exchange, ConnectionStats> statsMap;

    private volatile boolean running = false;

    public HealthMonitor(long checkIntervalMs) {
        this.checkIntervalMs = checkIntervalMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "health-monitor");
            thread.setDaemon(true);
            return thread;
        });
        this.statsMap = new EnumMap<>(Exchange.class);
    }

    /**
     * Registers an exchange connection for monitoring.
     */
    public void registerExchange(Exchange exchange, ConnectionChecker checker) {
        statsMap.put(exchange, new ConnectionStats(exchange, checker));
    }

    /**
     * Starts the health monitor.
     */
    public void start() {
        if (running) {
            return;
        }

        running = true;
        scheduler.scheduleAtFixedRate(
            this::performHealthCheck,
            checkIntervalMs,
            checkIntervalMs,
            TimeUnit.MILLISECONDS
        );

        LOGGER.info("Health monitor started (interval: {} ms)", checkIntervalMs);
    }

    /**
     * Stops the health monitor.
     */
    public void stop() {
        running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOGGER.info("Health monitor stopped");
    }

    /**
     * Performs a health check on all registered exchanges.
     */
    private void performHealthCheck() {
        for (ConnectionStats stats : statsMap.values()) {
            boolean isConnected = stats.checker.isConnected();
            stats.update(isConnected);

            if (!isConnected && stats.exchange != null) {
                LOGGER.warn("[HealthMonitor] {} is disconnected", stats.exchange);
            }
        }
    }

    /**
     * Logs a summary of connection statistics.
     */
    public void logSummary() {
        LOGGER.info("=== Health Monitor Summary ===");
        for (ConnectionStats stats : statsMap.values()) {
            LOGGER.info("{}: connected={}, disconnectCount={}, lastDisconnect={}",
                stats.exchange,
                stats.checker.isConnected(),
                stats.disconnectCount,
                stats.lastDisconnectTime
            );
        }
        LOGGER.info("=============================");
    }

    /**
     * Gets connection statistics for an exchange.
     */
    public ConnectionStats getStats(Exchange exchange) {
        return statsMap.get(exchange);
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Interface for checking connection status.
     */
    public interface ConnectionChecker {
        boolean isConnected();
        long getMessageCount();
        long getErrorCount();
    }

    /**
     * Statistics for a connection.
     */
    public static class ConnectionStats {
        private final Exchange exchange;
        private final ConnectionChecker checker;
        private volatile long disconnectCount = 0;
        private volatile long lastDisconnectTime = 0;

        public ConnectionStats(Exchange exchange, ConnectionChecker checker) {
            this.exchange = exchange;
            this.checker = checker;
        }

        private void update(boolean isConnected) {
            if (!isConnected) {
                disconnectCount++;
                lastDisconnectTime = System.currentTimeMillis();
            }
        }

        public Exchange getExchange() {
            return exchange;
        }

        public long getDisconnectCount() {
            return disconnectCount;
        }

        public long getLastDisconnectTime() {
            return lastDisconnectTime;
        }

        public long getMessageCount() {
            return checker.getMessageCount();
        }

        public long getErrorCount() {
            return checker.getErrorCount();
        }
    }
}
