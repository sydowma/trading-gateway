package io.trading.gateway.exchange.okx;

import io.trading.gateway.model.Exchange;
import io.trading.gateway.model.OrderBook;
import io.trading.gateway.model.OrderBookLevel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Fast OKX order book parser using char[] directly.
 */
public class FastCharOkxOrderBookParser {

    private static final int MAX_LEVELS = 50;
    private final ThreadLocal<ArrayList<OrderBookLevel>> bidsPool =
        ThreadLocal.withInitial(() -> new ArrayList<>(MAX_LEVELS));
    private final ThreadLocal<ArrayList<OrderBookLevel>> asksPool =
        ThreadLocal.withInitial(() -> new ArrayList<>(MAX_LEVELS));

    public OrderBook parseOrderBook(String message) {
        char[] chars = message.toCharArray();
        int len = chars.length;

        // Find "data":[
        int dataIdx = indexOf(chars, 0, "data");
        if (dataIdx < 0) return null;

        int objStart = dataIdx + 4;
        while (objStart < len && chars[objStart] != ':') objStart++;
        while (objStart < len && chars[objStart] != '[') objStart++;
        if (objStart >= len) return null;
        objStart++;
        while (objStart < len && chars[objStart] != '{') objStart++;
        if (objStart >= len) return null;

        ArrayList<OrderBookLevel> bids = bidsPool.get();
        ArrayList<OrderBookLevel> asks = asksPool.get();
        bids.clear();
        asks.clear();

        String symbol = null;
        long ts = 0;
        boolean isSnapshot = false;

        int i = objStart;
        boolean inBids = false;
        boolean inAsks = false;

        while (i < len - 10) {
            if (chars[i] == '"' && chars[i + 1] != '"') {
                switch (chars[i + 1]) {
                    case 'i': // instId
                        if (chars[i + 2] == 'n' && chars[i + 3] == 's' && chars[i + 4] == 't' &&
                            chars[i + 5] == 'I' && chars[i + 6] == 'd') {
                            i = extractString(chars, i + 6);
                            int start = lastExtractStart;
                            int end = lastExtractEnd;
                            if (end > start) {
                                symbol = new String(chars, start, end - start).replace("-", "");
                            }
                            i = lastExtractEnd;
                            continue;
                        }
                        break;
                    case 'a': // action or asks
                        if (chars[i + 2] == 'c' && chars[i + 3] == 't' && chars[i + 4] == 'i' &&
                            chars[i + 5] == 'o' && chars[i + 6] == 'n') {
                            // action
                            i = extractString(chars, i + 6);
                            int start = lastExtractStart;
                            int end = lastExtractEnd;
                            if (end > start) {
                                isSnapshot = "snapshot".equals(new String(chars, start, end - start));
                            }
                            i = lastExtractEnd;
                            continue;
                        } else if (chars[i + 2] == 's' && chars[i + 3] == 'k' && chars[i + 4] == 's') {
                            // asks array
                            inAsks = true;
                            inBids = false;
                            // Skip to asks array content
                            while (i < len && chars[i] != ':') i++;
                            i++;
                            while (i < len && (chars[i] == ' ' || chars[i] == ':')) i++;
                            while (i < len && chars[i] != '[') i++;
                            i++;
                            // Parse asks array
                            i = parseOrderBookArray(chars, i, asks);
                            continue;
                        }
                        break;
                    case 'b': // bids
                        if (chars[i + 2] == 'i' && chars[i + 3] == 'd' && chars[i + 4] == 's') {
                            inBids = true;
                            inAsks = false;
                            // Skip to bids array content
                            while (i < len && chars[i] != ':') i++;
                            i++;
                            while (i < len && (chars[i] == ' ' || chars[i] == ':')) i++;
                            while (i < len && chars[i] != '[') i++;
                            i++;
                            // Parse bids array
                            i = parseOrderBookArray(chars, i, bids);
                            continue;
                        }
                        break;
                    case 't': // ts
                        if (chars[i + 2] == 's') {
                            i = extractString(chars, i + 2);
                            int start = lastExtractStart;
                            int end = lastExtractEnd;
                            if (end > start) {
                                ts = parseLong(chars, start, end);
                            }
                            i = lastExtractEnd;
                            continue;
                        }
                        break;
                }
            }
            if (chars[i] == '}') break;
            i++;
        }

        if (symbol == null) return null;

        return new OrderBook(
            Exchange.OKX,
            symbol,
            ts,
            System.nanoTime(),
            List.copyOf(bids),
            List.copyOf(asks),
            isSnapshot
        );
    }

    private int parseOrderBookArray(char[] chars, int start, ArrayList<OrderBookLevel> levels) {
        int i = start;
        int depth = 1;

        while (i < chars.length && depth > 0) {
            if (chars[i] == '[') {
                depth++;
                i++;
                // Check if this is a level array ["price","size",...]
                if (depth == 2) {
                    BigDecimal price = null;
                    BigDecimal qty = null;

                    // Find first string (price)
                    while (i < chars.length && chars[i] != '"') i++;
                    if (chars[i] == '"') i++;
                    int priceStart = i;
                    while (i < chars.length && chars[i] != '"') i++;
                    price = new BigDecimal(chars, priceStart, i - priceStart);
                    i++; // Skip closing quote

                    // Find second string (size)
                    while (i < chars.length && chars[i] != '"') i++;
                    if (chars[i] == '"') i++;
                    int qtyStart = i;
                    while (i < chars.length && chars[i] != '"') i++;
                    qty = new BigDecimal(chars, qtyStart, i - qtyStart);

                    if (price != null && qty != null) {
                        levels.add(new OrderBookLevel(price, qty));
                    }
                }
            } else if (chars[i] == ']') {
                depth--;
            }
            i++;
        }
        return i;
    }

    private int lastExtractStart;
    private int lastExtractEnd;

    private int extractString(char[] chars, int i) {
        while (i < chars.length && chars[i] != ':') i++;
        i++;
        while (i < chars.length && (chars[i] == ' ' || chars[i] == '"')) i++;
        lastExtractStart = i;
        while (i < chars.length && chars[i] != '"') i++;
        lastExtractEnd = i;
        return i;
    }

    private int indexOf(char[] chars, int start, String str) {
        char[] strChars = str.toCharArray();
        outer:
        for (int i = start; i <= chars.length - strChars.length; i++) {
            for (int j = 0; j < strChars.length; j++) {
                if (chars[i + j] != strChars[j]) continue outer;
            }
            return i;
        }
        return -1;
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
