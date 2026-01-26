package io.trading.gateway.model;

import java.math.BigDecimal;

/**
 * Single price level in an order book.
 *
 * @param price    Price level
 * @param quantity Total quantity at this price level
 */
public record OrderBookLevel(
    BigDecimal price,
    BigDecimal quantity
) {
    public OrderBookLevel {
        if (price == null) {
            throw new IllegalArgumentException("price cannot be null");
        }
        if (quantity == null) {
            throw new IllegalArgumentException("quantity cannot be null");
        }
        if (price.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("price cannot be negative");
        }
        if (quantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("quantity cannot be negative");
        }
    }

    /**
     * Creates an empty order book level (price = 0, quantity = 0).
     */
    public static OrderBookLevel empty() {
        return new OrderBookLevel(BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
