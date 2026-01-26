package io.trading.gateway.model;

/**
 * Supported cryptocurrency exchanges.
 */
public enum Exchange {
    BINANCE("Binance"),
    OKX("OKX"),
    BYBIT("Bybit");

    private final String displayName;

    Exchange(String displayName) {
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
