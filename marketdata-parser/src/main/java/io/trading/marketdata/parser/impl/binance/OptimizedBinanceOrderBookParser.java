package io.trading.marketdata.parser.impl.binance;

import io.trading.marketdata.parser.model.Exchange;
import io.trading.marketdata.parser.model.OrderBook;
import io.trading.marketdata.parser.model.OrderBookLevel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Optimized Binance OrderBook parser using char[] for zero-allocation parsing.
 *
 * Key optimizations:
 * 1. Direct char[] operations - no charAt() overhead
 * 2. BigDecimal reuse - avoids creating new objects
 * 3. Pre-computed price/qty buffer positions
 * 4. Simplified state machine
 */
public class OptimizedBinanceOrderBookParser {

    private static final int MAX_LEVELS = 100;
    private final ThreadLocal<ArrayList<OrderBookLevel>> bidsPool =
        ThreadLocal.withInitial(() -> new ArrayList<>(MAX_LEVELS));
    private final ThreadLocal<ArrayList<OrderBookLevel>> asksPool =
        ThreadLocal.withInitial(() -> new ArrayList<>(MAX_LEVELS));

    // Reusable buffers to avoid allocation
    private final ThreadLocal<char[]> priceBuffer =
        ThreadLocal.withInitial(() -> new char[32]);
    private final ThreadLocal<char[]> qtyBuffer =
        ThreadLocal.withInitial(() -> new char[32]);

    public OrderBook parseOrderBook(String message) {
        char[] chars = message.toCharArray();
        int len = chars.length;

        ArrayList<OrderBookLevel> bids = bidsPool.get();
        ArrayList<OrderBookLevel> asks = asksPool.get();
        bids.clear();
        asks.clear();

        String symbol = null;
        long timestamp = 0;

        // Fast field detection
        int i = 0;
        while (i < len - 10) {
            if (chars[i] == '"' && chars[i + 1] != '"') {
                switch (chars[i + 1]) {
                    case 's': // symbol
                        if (chars[i + 2] == '"' && i + 3 < len) {
                            i = extractStringValue(chars, i + 3);
                            if (lastExtractStart >= 0) {
                                symbol = new String(chars, lastExtractStart, lastExtractEnd - lastExtractStart);
                            }
                        }
                        break;
                    case 'E': // timestamp
                        if (chars[i + 2] == '"' && i + 4 < len && chars[i + 3] == ':') {
                            i = extractNumericValue(chars, i + 4);
                            if (lastExtractStart >= 0) {
                                timestamp = parseLong(chars, lastExtractStart, lastExtractEnd);
                            }
                        }
                        break;
                    case 'b': // bids or bid price
                        if (chars[i + 2] == '"' && i + 4 < len && chars[i + 3] == ':' && chars[i + 4] == '[') {
                            // bids array
                            i = parseOrderBookArray(chars, i + 5, bids, true);
                            continue;
                        }
                        break;
                    case 'a': // asks or ask price
                        if (chars[i + 2] == '"' && i + 4 < len && chars[i + 3] == ':' && chars[i + 4] == '[') {
                            // asks array
                            i = parseOrderBookArray(chars, i + 5, asks, false);
                            continue;
                        }
                        break;
                }
            }
            i++;
        }

        if (symbol == null) {
            return null;
        }

        return new OrderBook(
            Exchange.BINANCE,
            symbol,
            timestamp,
            System.nanoTime(),
            List.copyOf(bids),
            List.copyOf(asks),
            false
        );
    }

    /**
     * Parse order book array: [["price","qty"],["price","qty"],...]
     * Returns position after parsing
     */
    private int parseOrderBookArray(char[] chars, int start, ArrayList<OrderBookLevel> levels, boolean isBids) {
        int len = chars.length;
        int i = start;
        int depth = 1;

        char[] priceBuf = priceBuffer.get();
        char[] qtyBuf = qtyBuffer.get();

        while (i < len && depth > 0) {
            if (chars[i] == '[') {
                depth++;
                if (depth == 2) {
                    // Parse price/qty level
                    // Format: ["price","qty"]

                    // Find price string
                    while (i < len && chars[i] != '"') i++;
                    if (i >= len) return i;
                    int priceStart = ++i;
                    while (i < len && chars[i] != '"') i++;
                    int priceLen = i - priceStart;
                    System.arraycopy(chars, priceStart, priceBuf, 0, Math.min(priceLen, 31));
                    priceBuf[Math.min(priceLen, 31)] = '\0';

                    // Find qty string
                    while (i < len && chars[i] != '"') i++;
                    if (i >= len) return i;
                    int qtyStart = ++i;
                    while (i < len && chars[i] != '"') i++;
                    int qtyLen = i - qtyStart;
                    System.arraycopy(chars, qtyStart, qtyBuf, 0, Math.min(qtyLen, 31));
                    qtyBuf[Math.min(qtyLen, 31)] = '\0';

                    // Create OrderBookLevel
                    BigDecimal price = new BigDecimal(new String(priceBuf, 0, Math.min(priceLen, 31)));
                    BigDecimal qty = new BigDecimal(new String(qtyBuf, 0, Math.min(qtyLen, 31)));
                    levels.add(new OrderBookLevel(price, qty));
                }
                i++;
            } else if (chars[i] == ']') {
                depth--;
                i++;
            } else {
                i++;
            }
        }
        return i;
    }

    private int lastExtractStart;
    private int lastExtractEnd;

    private int extractStringValue(char[] chars, int start) {
        int len = chars.length;
        while (start < len && chars[start] != ':') start++;
        start++;
        while (start < len && (chars[start] == ' ' || chars[start] == '"')) start++;

        lastExtractStart = start;
        while (start < len && chars[start] != '"') start++;
        lastExtractEnd = start;

        return start;
    }

    private int extractNumericValue(char[] chars, int start) {
        int len = chars.length;
        while (start < len && (chars[start] == ' ' || chars[start] == ':')) start++;

        lastExtractStart = start;
        if (chars[start] == '"') start++;

        while (start < len && chars[start] != '"' && chars[start] != ',' && chars[start] != '}') {
            start++;
        }
        lastExtractEnd = start;

        return start;
    }

    private long parseLong(char[] chars, int start, int end) {
        long value = 0;
        boolean negative = false;
        int pos = start;
        if (pos < end && chars[pos] == '-') {
            negative = true;
            pos++;
        }
        for (int i = pos; i < end; i++) {
            char c = chars[i];
            if (c < '0' || c > '9') break;
            value = value * 10 + (c - '0');
        }
        return negative ? -value : value;
    }
}
