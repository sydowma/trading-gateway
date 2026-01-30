package io.trading.gateway.exchange;

import io.trading.marketdata.parser.api.OutputFormat;
import io.trading.marketdata.parser.api.ParseResult;
import io.trading.marketdata.parser.impl.binance.BinanceMarketDataParser;
import io.trading.marketdata.parser.impl.okx.OkxMarketDataParser;
import io.trading.marketdata.parser.impl.bybit.BybitMarketDataParser;

/**
 * Performance test for market data parsers.
 * Measures parsing latency in microseconds.
 */
public class ParsePerformanceTest {

    // Sample WebSocket messages
    private static final String BINANCE_TICKER =
        "{\"e\":\"24hrTicker\",\"E\":123456789,\"s\":\"BTCUSDT\",\"c\":\"50000.00\",\"b\":\"49999.00\"," +
        "\"a\":\"50001.00\",\"B\":\"100.5\",\"A\":\"50.2\",\"v\":\"10000.5\",\"p\":\"250.5\",\"P\":\"0.5\"}";

    private static final String BINANCE_TRADE =
        "{\"e\":\"trade\",\"E\":123456789,\"s\":\"BTCUSDT\",\"t\":\"12345\",\"p\":\"50000.00\"," +
        "\"q\":\"0.5\",\"m\":false}";

    private static final String BINANCE_DEPTH =
        "{\"e\":\"depthUpdate\",\"E\":123456789,\"s\":\"BTCUSDT\",\"b\":[[\"50000.00\",\"100.5\"]]," +
        "\"a\":[[\"50001.00\",\"50.2\"]]}";

    private static final int WARMUP_ITERATIONS = 10_000;
    private static final int TEST_ITERATIONS = 100_000;

    public static void main(String[] args) {
        System.out.println("=== Market Data Parser Performance Test ===\n");
        System.out.println("Warmup iterations: " + WARMUP_ITERATIONS);
        System.out.println("Test iterations: " + TEST_ITERATIONS);
        System.out.println();

        // Test Binance Parser
        System.out.println("Testing Binance Parser...");
        testBinanceParser();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("\nAll tests completed!");
    }

    private static void testBinanceParser() {
        BinanceMarketDataParser parser = new BinanceMarketDataParser();

        // Warmup
        System.out.println("Warming up...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            parser.parse(BINANCE_TICKER, OutputFormat.JAVA);
            parser.parse(BINANCE_TRADE, OutputFormat.JAVA);
            parser.parse(BINANCE_DEPTH, OutputFormat.JAVA);
        }
        System.out.println("Warmup complete.\n");

        // Test Ticker parsing
        System.out.println("Testing Ticker parsing...");
        long tickerStart = System.nanoTime();
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            ParseResult result = parser.parse(BINANCE_TICKER, OutputFormat.JAVA);
        }
        long tickerEnd = System.nanoTime();
        double tickerAvgMicros = (tickerEnd - tickerStart) / (TEST_ITERATIONS * 1000.0);

        // Test Trade parsing
        System.out.println("Testing Trade parsing...");
        long tradeStart = System.nanoTime();
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            ParseResult result = parser.parse(BINANCE_TRADE, OutputFormat.JAVA);
        }
        long tradeEnd = System.nanoTime();
        double tradeAvgMicros = (tradeEnd - tradeStart) / (TEST_ITERATIONS * 1000.0);

        // Test Depth parsing
        System.out.println("Testing OrderBook parsing...");
        long depthStart = System.nanoTime();
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            ParseResult result = parser.parse(BINANCE_DEPTH, OutputFormat.JAVA);
        }
        long depthEnd = System.nanoTime();
        double depthAvgMicros = (depthEnd - depthStart) / (TEST_ITERATIONS * 1000.0);

        // Results
        System.out.println("\n=== BINANCE PARSER RESULTS ===");
        System.out.println("Ticker (24hrTicker):");
        System.out.println("  Average latency: " + String.format("%.2f", tickerAvgMicros) + " μs");
        System.out.println("  Throughput: " + String.format("%.0f", 1_000_000.0 / tickerAvgMicros) + " msgs/sec");

        System.out.println("\nTrade:");
        System.out.println("  Average latency: " + String.format("%.2f", tradeAvgMicros) + " μs");
        System.out.println("  Throughput: " + String.format("%.0f", 1_000_000.0 / tradeAvgMicros) + " msgs/sec");

        System.out.println("\nOrderBook (depthUpdate):");
        System.out.println("  Average latency: " + String.format("%.2f", depthAvgMicros) + " μs");
        System.out.println("  Throughput: " + String.format("%.0f", 1_000_000.0 / depthAvgMicros) + " msgs/sec");

        // Overall average
        double overallAvg = (tickerAvgMicros + tradeAvgMicros + depthAvgMicros) / 3.0;
        System.out.println("\nOverall average: " + String.format("%.2f", overallAvg) + " μs");
        System.out.println("Overall throughput: " + String.format("%.0f", 1_000_000.0 / overallAvg) + " msgs/sec");
    }
}
