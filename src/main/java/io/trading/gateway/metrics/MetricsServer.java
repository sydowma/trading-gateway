package io.trading.gateway.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import io.prometheus.client.exporter.common.TextFormat;
import io.trading.gateway.config.ExchangeConfig;
import io.trading.gateway.config.GatewayConfig;
import io.trading.gateway.config.SymbolConfig;
import io.trading.gateway.core.HealthMonitor;
import io.trading.gateway.model.DataType;
import io.trading.gateway.model.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import io.prometheus.client.CollectorRegistry;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP server for exposing Prometheus metrics and REST API.
 * Serves metrics, status, health, and config endpoints.
 */
public class MetricsServer implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetricsServer.class);

    private final int port;
    private final GatewayMetrics metrics;
    private final GatewayConfig config;
    private final HealthMonitor healthMonitor;
    private final CollectorRegistry registry;
    private final ObjectMapper objectMapper;
    private final Map<Exchange, ConnectorStatus> connectorStatusMap;
    private HttpServer server;
    private long startTime;

    public MetricsServer(int port, GatewayMetrics metrics, GatewayConfig config, HealthMonitor healthMonitor) {
        this.port = port;
        this.metrics = metrics;
        this.config = config;
        this.healthMonitor = healthMonitor;
        this.registry = metrics.getRegistry();
        this.objectMapper = new ObjectMapper();
        this.connectorStatusMap = new ConcurrentHashMap<>();
        this.startTime = System.currentTimeMillis();
    }

    /**
     * Registers the current status for a connector.
     */
    public void registerConnector(Exchange exchange, boolean connected, long messageCount, long errorCount) {
        connectorStatusMap.put(exchange, new ConnectorStatus(connected, messageCount, errorCount));
    }

    /**
     * Starts the HTTP server.
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);

        // Metrics endpoint (Prometheus)
        server.createContext("/metrics", handleMetrics());

        // Health endpoint (simple)
        server.createContext("/health", handleHealthSimple());

        // REST API endpoints
        server.createContext("/api/status", handleStatus());
        server.createContext("/api/health", handleHealth());
        server.createContext("/api/config", handleConfig());

        server.setExecutor(null);
        server.start();

        LOGGER.info("HTTP server started on port {}", port);
        LOGGER.info("  Prometheus: http://localhost:{}/metrics", port);
        LOGGER.info("  Health:     http://localhost:{}/health", port);
        LOGGER.info("  API Status: http://localhost:{}/api/status", port);
        LOGGER.info("  API Health: http://localhost:{}/api/health", port);
        LOGGER.info("  API Config: http://localhost:{}/api/config", port);
    }

    private HttpHandler handleMetrics() {
        return exchange -> {
            try {
                Writer writer = new StringWriter();
                TextFormat.write004(writer, registry.metricFamilySamples());
                String response = writer.toString();

                exchange.getResponseHeaders().set("Content-Type", TextFormat.CONTENT_TYPE_004);
                exchange.sendResponseHeaders(200, response.getBytes().length);

                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            } catch (Exception e) {
                LOGGER.error("Error serving metrics", e);
                exchange.sendResponseHeaders(500, 0);
            }
        };
    }

    private HttpHandler handleHealthSimple() {
        return exchange -> {
            try {
                String response = "OK";
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, response.getBytes().length);

                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            } catch (Exception e) {
                LOGGER.error("Error serving health", e);
                exchange.sendResponseHeaders(500, 0);
            }
        };
    }

    private HttpHandler handleStatus() {
        return exchange -> {
            try {
                Map<String, ExchangeStatusInfo> exchanges = new HashMap<>();

                for (ExchangeConfig exchangeConfig : config.exchangeConfigs()) {
                    Exchange exch = exchangeConfig.exchange();
                    ConnectorStatus status = connectorStatusMap.getOrDefault(
                        exch,
                        new ConnectorStatus(false, 0, 0)
                    );

                    // Count active subscriptions for this exchange
                    int activeSubscriptions = 0;
                    for (SymbolConfig symbolConfig : config.symbolConfigs()) {
                        if (symbolConfig.exchanges().contains(exch)) {
                            activeSubscriptions++;
                        }
                    }

                    // Get message counts from metrics
                    double tickerCount = metrics.getMessagesReceived(exch, DataType.TICKER);
                    double tradeCount = metrics.getMessagesReceived(exch, DataType.TRADES);
                    double orderBookCount = metrics.getMessagesReceived(exch, DataType.ORDER_BOOK);

                    exchanges.put(exch.name().toLowerCase(), new ExchangeStatusInfo(
                        exch.name(),
                        status.connected(),
                        (long) tickerCount,
                        (long) tradeCount,
                        (long) orderBookCount,
                        status.messageCount(),
                        status.errorCount(),
                        exchangeConfig.dataTypes().toString(),
                        activeSubscriptions
                    ));
                }

                StatusResponse statusResponse = new StatusResponse(
                    config.gatewayId(),
                    System.currentTimeMillis() - startTime,
                    exchanges
                );

                String response = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(statusResponse);
                sendJsonResponse(exchange, 200, response);
            } catch (Exception e) {
                LOGGER.error("Error handling status request", e);
                sendJsonResponse(exchange, 500, "{\"error\":\"Internal server error\"}");
            }
        };
    }

    private HttpHandler handleHealth() {
        return exchange -> {
            try {
                boolean healthy = true;
                StringBuilder message = new StringBuilder("All systems operational");

                for (ExchangeConfig exchangeConfig : config.exchangeConfigs()) {
                    if (exchangeConfig.enabled()) {
                        ConnectorStatus status = connectorStatusMap.getOrDefault(
                            exchangeConfig.exchange(),
                            new ConnectorStatus(false, 0, 0)
                        );
                        if (!status.connected()) {
                            healthy = false;
                            message = new StringBuilder(exchangeConfig.exchange().name() + " disconnected");
                        }
                    }
                }

                HealthResponse health = new HealthResponse(healthy, message.toString());
                String response = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(health);
                sendJsonResponse(exchange, healthy ? 200 : 503, response);
            } catch (Exception e) {
                LOGGER.error("Error handling health request", e);
                sendJsonResponse(exchange, 500, "{\"error\":\"Internal server error\"}");
            }
        };
    }

    private HttpHandler handleConfig() {
        return exchange -> {
            try {
                ConfigInfo configInfo = new ConfigInfo(
                    config.gatewayId(),
                    config.aeronDir(),
                    config.healthCheckMs(),
                    config.reconnectMaxRetries(),
                    config.metricsPort(),
                    config.exchangeConfigs().toString(),
                    config.symbolConfigs().toString()
                );

                String response = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(configInfo);
                sendJsonResponse(exchange, 200, response);
            } catch (Exception e) {
                LOGGER.error("Error handling config request", e);
                sendJsonResponse(exchange, 500, "{\"error\":\"Internal server error\"}");
            }
        };
    }

    private void sendJsonResponse(com.sun.net.httpserver.HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(statusCode, response.getBytes().length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes());
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
            LOGGER.info("HTTP server stopped");
        }
    }

    private record ConnectorStatus(boolean connected, long messageCount, long errorCount) {}

    private record StatusResponse(String gatewayId, long uptimeMs, Map<String, ExchangeStatusInfo> exchanges) {}
    private record ExchangeStatusInfo(String exchange, boolean connected, long tickerCount, long tradeCount,
                                     long orderBookCount, long totalMessages, long errors, String dataTypes, int subscriptions) {}
    private record HealthResponse(boolean healthy, String message) {}
    private record ConfigInfo(String gatewayId, String aeronDir, int healthCheckMs, int reconnectMaxRetries,
                              int metricsPort, String exchanges, String symbols) {}
}
