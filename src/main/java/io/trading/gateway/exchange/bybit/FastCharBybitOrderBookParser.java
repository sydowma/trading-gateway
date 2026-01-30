package io.trading.gateway.exchange.bybit;

import io.trading.gateway.model.Exchange;
import io.trading.gateway.model.OrderBook;
import io.trading.gateway.model.OrderBookLevel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Fast Bybit order book parser using char[] directly.
 */
public class FastCharBybitOrderBookParser {

    private static final int MAX_LEVELS = 50;
    private final ThreadLocal<ArrayList<OrderBookLevel>> bidsPool =
        ThreadLocal.withInitial(() -> new ArrayList<>(MAX_LEVELS));
    private final ThreadLocal<ArrayList<OrderBookLevel>> asksPool =
        ThreadLocal.withInitial(() -> new ArrayList<>(MAX_LEVELS));

    public OrderBook parseOrderBook(String message) {
        char[] chars = message.toCharArray();
        int len = chars.length;

        // Find "data":{
        int dataIdx = indexOf(chars, 0, "data");
        if (dataIdx < 0) return null;

        int objStart = dataIdx + 4;
        while (objStart < len && chars[objStart] != ':') objStart++;
        while (objStart < len && chars[objStart] != '{') objStart++;
        if (objStart >= len) return null;

        ArrayList<OrderBookLevel> bids = bidsPool.get();
        ArrayList<OrderBookLevel> asks = asksPool.get();
        bids.clear();
        asks.clear();

        String symbol = null;
        long ts = 0;

        int i = objStart;
        while (i < len - 7) {
            if (chars[i] == '"' && chars[i + 1] != '"') {
                switch (chars[i + 1]) {
                    case 's': // symbol (s in Bybit) or seq
                        if (chars[i + 2] == 'e' && chars[i + 3] == 'q') {
                            // skip seq field
                            i = skipValue(chars, i + 3);
                            continue;
                        } else if (chars[i + 2] == '"') {
                            // symbol field "s":"BTCUSDT"
                            i = extractString(chars, i + 1);
                            int start = lastExtractStart;
                            int end = lastExtractEnd;
                            if (end > start) {
                                symbol = new String(chars, start, end - start);
                            }
                            i = lastExtractEnd;
                            continue;
                        }
                        break;
                    case 'b': // bids array (b in Bybit)
                        if (chars[i + 2] == '"') {
                            // Skip to array
                            while (i < len && chars[i] != ':') i++;
                            i++;
                            while (i < len && (chars[i] == ' ' || chars[i] == ':')) i++;
                            while (i < len && chars[i] != '[') i++;
                            i++;
                            // Parse bids
                            i = parseBybitOrderBookArray(chars, i, bids);
                            continue;
                        }
                        break;
                    case 'a': // asks array (a in Bybit)
                        if (chars[i + 2] == '"') {
                            // Skip to array
                            while (i < len && chars[i] != ':') i++;
                            i++;
                            while (i < len && (chars[i] == ' ' || chars[i] == ':')) i++;
                            while (i < len && chars[i] != '[') i++;
                            i++;
                            // Parse asks
                            i = parseBybitOrderBookArray(chars, i, asks);
                            continue;
                        }
                        break;
                    case 'E': // E (updateId) or other fields - skip
                        break;
                    case 'T': // timestamp
                        if (chars[i + 2] == 's' || chars[i + 2] == '"') {
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
            Exchange.BYBIT,
            symbol,
            ts,
            System.nanoTime(),
            List.copyOf(bids),
            List.copyOf(asks),
            false
        );
    }

    private int parseBybitOrderBookArray(char[] chars, int start, ArrayList<OrderBookLevel> levels) {
        int i = start;
        int depth = 1;

        while (i < chars.length && depth > 0) {
            if (chars[i] == '[') {
                depth++;
                i++;
                if (depth == 2) {
                    // Parse level: ["price","size"]
                    BigDecimal price = null;
                    BigDecimal qty = null;

                    // Find price (first string)
                    while (i < chars.length && chars[i] != '"') i++;
                    if (chars[i] == '"') i++;
                    int priceStart = i;
                    while (i < chars.length && chars[i] != '"') i++;
                    if (i < chars.length) {
                        price = new BigDecimal(chars, priceStart, i - priceStart);
                        i++;
                    }

                    // Find qty (second string)
                    while (i < chars.length && chars[i] != '"') i++;
                    if (chars[i] == '"') i++;
                    int qtyStart = i;
                    while (i < chars.length && chars[i] != '"') i++;
                    if (i < chars.length) {
                        qty = new BigDecimal(chars, qtyStart, i - qtyStart);
                    }

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

    private int skipValue(char[] chars, int i) {
        while (i < chars.length && chars[i] != ':') i++;
        i++;
        while (i < chars.length && (chars[i] == ' ' || chars[i] == '\t')) i++;
        if (chars[i] == '"') {
            i++; // skip opening quote
            while (i < chars.length && chars[i] != '"') i++;
        } else if (chars[i] == '[') {
            // array
            int depth = 1;
            i++;
            while (i < chars.length && depth > 0) {
                if (chars[i] == '[') depth++;
                else if (chars[i] == ']') depth--;
                i++;
            }
        } else {
            // number or other
            while (i < chars.length && chars[i] != ',' && chars[i] != '}') i++;
        }
        return i;
    }

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
