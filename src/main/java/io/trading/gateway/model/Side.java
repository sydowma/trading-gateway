package io.trading.gateway.model;

/**
 * Trade side (buy or sell).
 */
public enum Side {
    BUY,
    SELL,
    UNKNOWN;

    public static Side fromString(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        return switch (value.toLowerCase()) {
            case "buy", "b" -> BUY;
            case "sell", "s" -> SELL;
            default -> UNKNOWN;
        };
    }
}
