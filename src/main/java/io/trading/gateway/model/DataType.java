package io.trading.gateway.model;

/**
 * Market data types supported by the gateway.
 */
public enum DataType {
    TICKER("ticker"),
    TRADES("trades"),
    ORDER_BOOK("order_book");

    private final String displayName;

    DataType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
