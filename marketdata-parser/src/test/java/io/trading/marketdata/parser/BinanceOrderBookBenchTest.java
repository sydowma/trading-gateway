package io.trading.marketdata.parser;

import io.trading.marketdata.parser.impl.binance.BinanceMarketDataParser;
import io.trading.marketdata.parser.api.OutputFormat;

/**
 * Performance test for Binance OrderBook parser.
 */
public class BinanceOrderBookBenchTest {
    public static void main(String[] args) {
        // Real Binance depthUpdate message
        String message = "{\"e\":\"depthUpdate\",\"E\":1704067200000,\"s\":\"BTCUSDT\",\"U\":123456789,\"u\":123456790,\"b\":[[\"43250.20000000\",\"0.50000000\"],[\"43250.10000000\",\"1.20000000\"],[\"43250.00000000\",\"2.50000000\"]],\"a\":[[\"43250.30000000\",\"0.80000000\"],[\"43250.40000000\",\"1.50000000\"],[\"43250.50000000\",\"2.00000000\"]]}";

        BinanceMarketDataParser parser = new BinanceMarketDataParser();

        // Warmup
        for (int i = 0; i < 10000; i++) {
            parser.parse(message, OutputFormat.JAVA);
        }

        // Benchmark
        int iterations = 100000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            parser.parse(message, OutputFormat.JAVA);
        }
        long end = System.nanoTime();

        double avgMicros = (end - start) / (iterations * 1000.0);
        System.out.printf("Average parse time: %.2f μs%n", avgMicros);
    }
}
