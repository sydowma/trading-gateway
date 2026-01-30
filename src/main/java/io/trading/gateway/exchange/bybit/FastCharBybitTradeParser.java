package io.trading.gateway.exchange.bybit;

import io.trading.gateway.model.Exchange;
import io.trading.gateway.model.Side;
import io.trading.gateway.model.Trade;

import java.math.BigDecimal;

/**
 * Fast Bybit trade parser using char[] directly.
 * Bybit trade format: {"topic":"publicTrade.BTCUSDT","data":[{"i":"...","T":...,"p":"...","v":"...","S":"...","s":"..."}]}
 */
public class FastCharBybitTradeParser {

    public Trade parseTrade(String message) {
        char[] chars = message.toCharArray();
        int len = chars.length;

        // Find "data":
        int dataIdx = indexOf(chars, 0, "data");
        if (dataIdx < 0) return null;

        // Find the array start "["
        int arrayStart = dataIdx + 4;
        while (arrayStart < len && chars[arrayStart] != '[') arrayStart++;
        if (arrayStart >= len) return null;

        // Find the first object "{"
        int objStart = arrayStart + 1;
        while (objStart < len && chars[objStart] != '{') objStart++;
        if (objStart >= len) return null;

        String symbol = null;
        String tradeId = null;
        Side side = null;
        BigDecimal price = null;
        BigDecimal qty = null;
        long ts = 0;

        int i = objStart;
        while (i < len - 7) {
            if (chars[i] == '"' && chars[i + 1] != '"') {
                switch (chars[i + 1]) {
                    case 's': // symbol (s) or side (S)
                        if (chars[i + 2] == 'e' && chars[i + 3] == 'q') {
                            // skip "seq" field
                            i = skipValue(chars, i + 3);
                            continue;
                        }
                        break;
                    case 'S': // Side (capital S)
                        if (chars[i + 2] == '"') {
                            i = extractString(chars, i + 1);
                            int start = lastExtractStart;
                            int end = lastExtractEnd;
                            if (end > start) {
                                String sideStr = new String(chars, start, end - start);
                                side = "Buy".equals(sideStr) ? Side.BUY : Side.SELL;
                            }
                            i = lastExtractEnd;
                            continue;
                        }
                        break;
                    case 'T': // Trade timestamp (capital T)
                        if (chars[i + 2] == '"') {
                            i = extractString(chars, i + 1);
                            int start = lastExtractStart;
                            int end = lastExtractEnd;
                            if (end > start) {
                                ts = parseLong(chars, start, end);
                            }
                            i = lastExtractEnd;
                            continue;
                        }
                        break;
                    case 'p': // price
                        if (chars[i + 2] == '"') {
                            price = extractBigDecimal(chars, i + 1);
                            i = lastExtractEnd;
                            continue;
                        }
                        break;
                    case 'v': // volume/quantity
                        if (chars[i + 2] == '"') {
                            qty = extractBigDecimal(chars, i + 1);
                            i = lastExtractEnd;
                            continue;
                        }
                        break;
                    case 'i': // trade id
                        if (chars[i + 2] == '"') {
                            i = extractString(chars, i + 1);
                            int start = lastExtractStart;
                            int end = lastExtractEnd;
                            if (end > start) {
                                tradeId = new String(chars, start, end - start);
                            }
                            i = lastExtractEnd;
                            continue;
                        }
                        break;
                    case 'B': // BT field (skip)
                        if (chars[i + 2] == 'T') {
                            i = skipValue(chars, i + 2);
                            continue;
                        }
                        break;
                    case 'R': // RPI field (skip)
                        if (chars[i + 2] == 'P' && chars[i + 3] == 'I') {
                            i = skipValue(chars, i + 3);
                            continue;
                        }
                        break;
                }
            }
            if (chars[i] == '}') break;
            i++;
        }

        // Extract symbol from topic if not found in data
        if (symbol == null) {
            int topicIdx = indexOf(chars, 0, "topic");
            if (topicIdx >= 0) {
                int dotIdx = indexOf(chars, topicIdx, ".");
                if (dotIdx >= 0) {
                    int start = dotIdx + 1;
                    int end = start;
                    while (end < len && chars[end] != '"' && chars[end] != ',') end++;
                    if (end > start) {
                        symbol = new String(chars, start, end - start);
                    }
                }
            }
        }

        if (symbol == null) return null;

        return new Trade(
            Exchange.BYBIT,
            symbol,
            ts,
            System.nanoTime(),
            tradeId != null ? tradeId : String.valueOf(ts),
            price != null ? price : BigDecimal.ZERO,
            qty != null ? qty : BigDecimal.ZERO,
            side != null ? side : Side.BUY
        );
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
        } else if (chars[i] == 't' || chars[i] == 'f') {
            // true or false
            while (i < chars.length && chars[i] != ',' && chars[i] != '}') i++;
        } else {
            // number
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

    private BigDecimal extractBigDecimal(char[] chars, int i) {
        while (i < chars.length && chars[i] != ':') i++;
        i++;
        while (i < chars.length && (chars[i] == ' ' || chars[i] == '"')) i++;
        int start = i;
        while (i < chars.length && chars[i] != '"') i++;
        lastExtractStart = start;
        lastExtractEnd = i;
        return new BigDecimal(chars, start, i - start);
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
