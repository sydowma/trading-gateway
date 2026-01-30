package io.trading.gateway.exchange.binance;

import java.util.concurrent.TimeUnit;

/**
 * Simple performance comparison between hash-based and equals-based string comparison.
 *
 * Run this with: java -XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation
 */
public class SimplePerformanceTest {

    private static final String TRADE_MESSAGE = "{\"e\":\"trade\",\"E\":123456789,\"s\":\"BNBBTC\",\"t\":\"12345\",\"p\":\"0.001\",\"q\":\"100\"}";
    private static final int HASH_TRADE = hash("trade");
    private static final int WARMUP_ITERATIONS = 100_000;
    private static final int TEST_ITERATIONS = 1_000_000;

    public static void main(String[] args) {
        System.out.println("=== String Comparison Performance Test ===\n");
        System.out.println("Warmup iterations: " + WARMUP_ITERATIONS);
        System.out.println("Test iterations: " + TEST_ITERATIONS);
        System.out.println();

        // Warmup phase
        System.out.println("Warming up...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            hashComparison();
            equalsComparison();
        }
        System.out.println("Warmup complete.\n");

        // Test hash comparison
        System.out.println("Testing hash-based comparison...");
        long hashStart = System.nanoTime();
        int hashMatchCount = 0;
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            if (hashComparison()) {
                hashMatchCount++;
            }
        }
        long hashEnd = System.nanoTime();
        double hashMs = (hashEnd - hashStart) / 1_000_000.0;

        // Test equals comparison
        System.out.println("Testing equals-based comparison...");
        long equalsStart = System.nanoTime();
        int equalsMatchCount = 0;
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            if (equalsComparison()) {
                equalsMatchCount++;
            }
        }
        long equalsEnd = System.nanoTime();
        double equalsMs = (equalsEnd - equalsStart) / 1_000_000.0;

        // Results
        System.out.println("\n=== RESULTS ===");
        System.out.println("Hash-based comparison:");
        System.out.println("  Time: " + String.format("%.2f", hashMs) + " ms");
        System.out.println("  Matches: " + hashMatchCount);
        System.out.println("  Ops/sec: " + String.format("%.2f", TEST_ITERATIONS / (hashMs / 1000.0)));

        System.out.println("\nEquals-based comparison:");
        System.out.println("  Time: " + String.format("%.2f", equalsMs) + " ms");
        System.out.println("  Matches: " + equalsMatchCount);
        System.out.println("  Ops/sec: " + String.format("%.2f", TEST_ITERATIONS / (equalsMs / 1000.0)));

        double speedup = equalsMs / hashMs;
        System.out.println("\n=== SUMMARY ===");
        System.out.println("Hash-based is " + String.format("%.2f", speedup) + "x faster");
        System.out.println("Time saved: " + String.format("%.2f", equalsMs - hashMs) + " ms");
        System.out.println("Improvement: " + String.format("%.1f", (speedup - 1) * 100) + "%");
    }

    /**
     * Hash-based comparison (production implementation).
     */
    private static boolean hashComparison() {
        return getEventTypeHash(TRADE_MESSAGE) == HASH_TRADE;
    }

    /**
     * Traditional substring + equals() comparison.
     */
    private static boolean equalsComparison() {
        return "trade".equals(extractEventType(TRADE_MESSAGE));
    }

    /**
     * Production hash-based event type extraction.
     */
    private static int getEventTypeHash(String message) {
        int len = message.length();
        int i = 0;

        // Skip opening brace
        while (i < len && message.charAt(i) != '{') {
            i++;
        }
        i++; // skip '{'

        // Look for "e" field
        while (i < len - 3) {
            if (message.charAt(i) == '"' &&
                message.charAt(i + 1) == 'e' &&
                message.charAt(i + 2) == '"') {

                // Found "e", skip to value
                i += 3; // skip "e"
                while (i < len && (message.charAt(i) == ':' || message.charAt(i) == ' ')) {
                    i++;
                }

                // Extract value
                if (i < len && message.charAt(i) == '"') {
                    i++; // skip opening quote
                    int start = i;
                    while (i < len && message.charAt(i) != '"') {
                        i++;
                    }
                    // Fast hash comparison
                    return hash(message, start, i);
                }
                break;
            }
            i++;
        }

        return 0;
    }

    /**
     * Traditional event type extraction using substring.
     */
    private static String extractEventType(String message) {
        int len = message.length();
        int i = 0;

        // Skip opening brace
        while (i < len && message.charAt(i) != '{') {
            i++;
        }
        i++; // skip '{'

        // Look for "e" field
        while (i < len - 3) {
            if (message.charAt(i) == '"' &&
                message.charAt(i + 1) == 'e' &&
                message.charAt(i + 2) == '"') {

                // Found "e", skip to value
                i += 3; // skip "e"
                while (i < len && (message.charAt(i) == ':' || message.charAt(i) == ' ')) {
                    i++;
                }

                // Extract value
                if (i < len && message.charAt(i) == '"') {
                    i++; // skip opening quote
                    int start = i;
                    while (i < len && message.charAt(i) != '"') {
                        i++;
                    }
                    return message.substring(start, i);
                }
                break;
            }
            i++;
        }

        return "";
    }

    /**
     * Compute rolling hash for string comparison.
     */
    private static int hash(String str) {
        int h = 0;
        for (int i = 0; i < str.length(); i++) {
            h = 31 * h + str.charAt(i);
        }
        return h;
    }

    private static int hash(String str, int start, int end) {
        int h = 0;
        for (int i = start; i < end; i++) {
            h = 31 * h + str.charAt(i);
        }
        return h;
    }
}
