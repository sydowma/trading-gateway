package io.trading.marketdata.parser.api;

/**
 * Output format options for parsed market data.
 */
public enum OutputFormat {
    /**
     * Return Java objects (default, zero overhead).
     * This is the fastest option as it returns the parsed object directly.
     */
    JAVA,

    /**
     * Return SBE (Simple Binary Encoding) binary format.
     * Useful for high-performance message transmission.
     */
    SBE,

    /**
     * Return JSON string format.
     * Useful for debugging and interoperability.
     */
    JSON
}
