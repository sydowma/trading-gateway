package io.trading.gateway.config;

import io.trading.gateway.model.DataType;
import io.trading.gateway.model.Exchange;

import java.util.Set;

/**
 * Configuration for the Trading Gateway.
 *
 * @param gatewayId           Unique gateway instance identifier
 * @param aeronDir            Aeron directory for media driver
 * @param exchangeConfigs     Exchange configurations
 * @param symbolConfigs       Symbol configurations
 * @param healthCheckMs       Health check interval in milliseconds
 * @param reconnectMaxRetries Maximum reconnect retries
 * @param metricsPort         Port for Prometheus metrics HTTP server
 */
public record GatewayConfig(
    String gatewayId,
    String aeronDir,
    Set<ExchangeConfig> exchangeConfigs,
    Set<SymbolConfig> symbolConfigs,
    int healthCheckMs,
    int reconnectMaxRetries,
    int metricsPort
) {
    private static final int DEFAULT_HEALTH_CHECK_MS = 5000;
    private static final int DEFAULT_RECONNECT_MAX_RETRIES = 10;
    private static final int DEFAULT_METRICS_PORT = 9090;

    public GatewayConfig {
        if (gatewayId == null || gatewayId.isEmpty()) {
            throw new IllegalArgumentException("gatewayId cannot be null or empty");
        }
        if (aeronDir == null || aeronDir.isEmpty()) {
            throw new IllegalArgumentException("aeronDir cannot be null or empty");
        }
        if (exchangeConfigs == null || exchangeConfigs.isEmpty()) {
            throw new IllegalArgumentException("exchangeConfigs cannot be null or empty");
        }
        if (symbolConfigs == null || symbolConfigs.isEmpty()) {
            throw new IllegalArgumentException("symbolConfigs cannot be null or empty");
        }
        if (healthCheckMs <= 0) {
            throw new IllegalArgumentException("healthCheckMs must be positive");
        }
        if (reconnectMaxRetries < 0) {
            throw new IllegalArgumentException("reconnectMaxRetries cannot be negative");
        }
        if (metricsPort < 1 || metricsPort > 65535) {
            throw new IllegalArgumentException("metricsPort must be between 1 and 65535");
        }
    }

    /**
     * Loads configuration from environment variables.
     *
     * Environment variables:
     * - GATEWAY_ID: Gateway instance ID (default: "gateway-0")
     * - EXCHANGES: Exchange configs (e.g., "binance:true:ticker,trade,book;okx:true:ticker,trade")
     * - SYMBOLS: Symbol configs (e.g., "BTCUSDT:binance,okx,bybit;ETHUSDT:binance,okx")
     * - AERON_DIR: Aeron directory (default: "/dev/shm/trading-gateway-{gatewayId}")
     * - HEALTH_CHECK_MS: Health check interval (default: 5000)
     * - RECONNECT_MAX_RETRIES: Max reconnect retries (default: 10)
     */
    public static GatewayConfig fromEnv() {
        String gatewayId = System.getenv("GATEWAY_ID");
        if (gatewayId == null || gatewayId.isEmpty()) {
            gatewayId = "gateway-0";
        }

        String aeronDir = System.getenv("AERON_DIR");
        if (aeronDir == null || aeronDir.isEmpty()) {
            aeronDir = "/dev/shm/trading-gateway-" + gatewayId;
        }

        String exchangesStr = System.getenv("EXCHANGES");
        if (exchangesStr == null || exchangesStr.isEmpty()) {
            exchangesStr = "binance:true:ticker,trade,book;okx:true:ticker,trade;bybit:true:ticker,trade";
        }

        String symbolsStr = System.getenv("SYMBOLS");
        if (symbolsStr == null || symbolsStr.isEmpty()) {
            symbolsStr = "BTCUSDT:binance,okx,bybit;ETHUSDT:binance,okx";
        }

        int healthCheckMs = parseIntEnv("HEALTH_CHECK_MS", DEFAULT_HEALTH_CHECK_MS);
        int reconnectMaxRetries = parseIntEnv("RECONNECT_MAX_RETRIES", DEFAULT_RECONNECT_MAX_RETRIES);
        int metricsPort = parseIntEnv("METRICS_PORT", DEFAULT_METRICS_PORT);

        Set<ExchangeConfig> exchangeConfigs = java.util.Arrays.stream(exchangesStr.split(";"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(ExchangeConfig::fromString)
            .collect(java.util.stream.Collectors.toSet());

        Set<SymbolConfig> symbolConfigs = java.util.Arrays.stream(symbolsStr.split(";"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(SymbolConfig::fromString)
            .collect(java.util.stream.Collectors.toSet());

        return new GatewayConfig(
            gatewayId,
            aeronDir,
            exchangeConfigs,
            symbolConfigs,
            healthCheckMs,
            reconnectMaxRetries,
            metricsPort
        );
    }

    private static int parseIntEnv(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            System.err.println("Invalid " + key + " value: " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    /**
     * Creates a new builder for GatewayConfig.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for GatewayConfig.
     */
    public static class Builder {
        private String gatewayId = "gateway-0";
        private String aeronDir;
        private final java.util.HashSet<ExchangeConfig> exchangeConfigs = new java.util.HashSet<>();
        private final java.util.HashSet<SymbolConfig> symbolConfigs = new java.util.HashSet<>();
        private int healthCheckMs = DEFAULT_HEALTH_CHECK_MS;
        private int reconnectMaxRetries = DEFAULT_RECONNECT_MAX_RETRIES;
        private int metricsPort = DEFAULT_METRICS_PORT;

        public Builder gatewayId(String gatewayId) {
            this.gatewayId = gatewayId;
            return this;
        }

        public Builder aeronDir(String aeronDir) {
            this.aeronDir = aeronDir;
            return this;
        }

        public Builder addExchange(Exchange exchange, boolean enabled, DataType... dataTypes) {
            this.exchangeConfigs.add(new ExchangeConfig(
                exchange,
                enabled,
                Set.of(dataTypes)
            ));
            return this;
        }

        public Builder addSymbol(String symbol, Exchange... exchanges) {
            this.symbolConfigs.add(new SymbolConfig(
                symbol,
                Set.of(exchanges)
            ));
            return this;
        }

        public Builder healthCheckMs(int healthCheckMs) {
            this.healthCheckMs = healthCheckMs;
            return this;
        }

        public Builder reconnectMaxRetries(int reconnectMaxRetries) {
            this.reconnectMaxRetries = reconnectMaxRetries;
            return this;
        }

        public Builder metricsPort(int metricsPort) {
            this.metricsPort = metricsPort;
            return this;
        }

        public GatewayConfig build() {
            if (aeronDir == null || aeronDir.isEmpty()) {
                aeronDir = "/dev/shm/trading-gateway-" + gatewayId;
            }
            if (exchangeConfigs.isEmpty()) {
                throw new IllegalStateException("At least one exchange config must be added");
            }
            if (symbolConfigs.isEmpty()) {
                throw new IllegalStateException("At least one symbol config must be added");
            }
            return new GatewayConfig(
                gatewayId,
                aeronDir,
                Set.copyOf(exchangeConfigs),
                Set.copyOf(symbolConfigs),
                healthCheckMs,
                reconnectMaxRetries,
                metricsPort
            );
        }
    }
}
