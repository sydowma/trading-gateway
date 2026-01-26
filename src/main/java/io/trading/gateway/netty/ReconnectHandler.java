package io.trading.gateway.netty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Handles automatic reconnection for WebSocket clients with exponential backoff.
 */
public class ReconnectHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReconnectHandler.class);

    private static final long INITIAL_DELAY_MS = 1000; // 1 second
    private static final long MAX_DELAY_MS = 60000;    // 1 minute
    private static final double BACKOFF_MULTIPLIER = 1.5;

    private final String name;
    private final int maxRetries;
    private final Runnable connectAction;

    private ScheduledExecutorService scheduler;
    private int retryCount = 0;
    private long currentDelay = INITIAL_DELAY_MS;
    private volatile boolean running = false;

    /**
     * Creates a new reconnect handler.
     *
     * @param name          Friendly name for logging
     * @param maxRetries    Maximum number of reconnection attempts (-1 for unlimited)
     * @param connectAction Action to perform when reconnecting
     */
    public ReconnectHandler(String name, int maxRetries, Runnable connectAction) {
        this.name = name;
        this.maxRetries = maxRetries;
        this.connectAction = connectAction;
    }

    /**
     * Starts the reconnect handler.
     */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, name + "-reconnect-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        LOGGER.info("{}: Reconnect handler started", name);
    }

    /**
     * Stops the reconnect handler.
     */
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        retryCount = 0;
        currentDelay = INITIAL_DELAY_MS;
        LOGGER.info("{}: Reconnect handler stopped", name);
    }

    /**
     * Schedules a reconnection attempt with exponential backoff.
     */
    public void scheduleReconnect() {
        if (!running) {
            LOGGER.warn("{}: Reconnect handler not running", name);
            return;
        }

        if (maxRetries >= 0 && retryCount >= maxRetries) {
            LOGGER.error("{}: Max reconnect retries ({}) reached, giving up", name, maxRetries);
            stop();
            return;
        }

        retryCount++;
        LOGGER.info("{}: Scheduling reconnect attempt {} in {} ms",
            name, retryCount, currentDelay);

        scheduler.schedule(() -> {
            if (running) {
                try {
                    LOGGER.info("{}: Attempting reconnection #{}", name, retryCount);
                    connectAction.run();
                } catch (Exception e) {
                    LOGGER.error("{}: Reconnect attempt failed", name, e);
                    // Schedule next attempt with increased delay
                    currentDelay = Math.min(
                        (long) (currentDelay * BACKOFF_MULTIPLIER),
                        MAX_DELAY_MS
                    );
                    scheduleReconnect();
                }
            }
        }, currentDelay, TimeUnit.MILLISECONDS);
    }

    /**
     * Resets the reconnect state (called on successful connection).
     */
    public void reset() {
        retryCount = 0;
        currentDelay = INITIAL_DELAY_MS;
        LOGGER.debug("{}: Reconnect state reset", name);
    }

    /**
     * Returns whether the handler is running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns the current retry count.
     */
    public int getRetryCount() {
        return retryCount;
    }
}
