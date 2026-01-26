package io.trading.gateway;

import io.trading.gateway.config.GatewayConfig;
import io.trading.gateway.core.GatewayController;
import org.agrona.concurrent.ShutdownSignalBarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the Trading Gateway application.
 */
public class TradingGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(TradingGateway.class);

    public static void main(String[] args) {
        // Configure logging to use async for performance
        System.setProperty("logback.configurationFile", "config/logback.xml");

        LOGGER.info("========================================");
        LOGGER.info("   Trading Gateway Starting...");
        LOGGER.info("========================================");

        try {
            // Load configuration from environment variables
            GatewayConfig config = GatewayConfig.fromEnv();
            LOGGER.info("Configuration loaded:");
            LOGGER.info("  Gateway ID: {}", config.gatewayId());
            LOGGER.info("  Aeron Dir: {}", config.aeronDir());
            LOGGER.info("  Exchange Configs: {}", config.exchangeConfigs().size());
            LOGGER.info("  Symbol Configs: {}", config.symbolConfigs().size());

            // Create and start the controller
            GatewayController controller = new GatewayController(config);
            controller.start();

            // Setup shutdown hooks for graceful termination
            ShutdownSignalBarrier shutdownBarrier = controller.getShutdownBarrier();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOGGER.info("Shutdown hook triggered");
                shutdownBarrier.signal();
            }));

            // Wait for shutdown signal
            controller.waitForShutdown();

            // Cleanup
            controller.close();

        } catch (Exception e) {
            LOGGER.error("Fatal error in Trading Gateway", e);
            System.exit(1);
        }

        LOGGER.info("Trading Gateway exited");
    }
}
