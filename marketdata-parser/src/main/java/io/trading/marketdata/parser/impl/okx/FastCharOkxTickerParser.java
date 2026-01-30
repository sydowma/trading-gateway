package io.trading.marketdata.parser.impl.okx;

import io.trading.marketdata.parser.model.Exchange;
import io.trading.marketdata.parser.model.Ticker;

import java.math.BigDecimal;

/**
 * Fast OKX ticker parser using char[] directly with BigDecimal.
 */
public class FastCharOkxTickerParser {

    public Ticker parseTicker(String message) {
        char[] chars = message.toCharArray();
        int len = chars.length;

        // Find "data":[
        int dataIdx = indexOf(chars, 0, "data");
        if (dataIdx < 0) return null;

        // Skip to array content
        int objStart = dataIdx + 4;
        while (objStart < len && chars[objStart] != '[') objStart++;
        if (objStart >= len) return null;
        objStart++;
        while (objStart < len && chars[objStart] != '{') objStart++;
        if (objStart >= len) return null;

        // Parse fields
        String symbol = null;
        long ts = 0;
        BigDecimal last = null;
        BigDecimal bidPx = null;
        BigDecimal askPx = null;
        BigDecimal bidSz = null;
        BigDecimal askSz = null;
        BigDecimal vol24h = null;
        BigDecimal changeUtc8 = null;

        int i = objStart;
        while (i < len - 7) {
            if (chars[i] == '"' && chars[i + 1] != '"') {
                // Quick field detection
                switch (chars[i + 1]) {
                    case 'i': // instId
                        if (chars[i + 2] == 'n' && chars[i + 3] == 's' && chars[i + 4] == 't' && chars[i + 5] == 'I' && chars[i + 6] == 'd') {
                            i = extractString(chars, i + 6);
                            int valStart = lastExtractStart;
                            int valEnd = lastExtractEnd;
                            if (valEnd > valStart) {
                                symbol = new String(chars, valStart, valEnd - valStart).replace("-", "");
                            }
                            continue;
                        }
                        break;
                    case 'l': // last
                        if (chars[i + 2] == 'a' && chars[i + 3] == 's' && chars[i + 4] == 't') {
                            last = extractBigDecimal(chars, i + 4);
                            i = lastExtractEnd;
                            continue;
                        }
                        break;
                    case 'b': // bidPx or bidSz
                        if (chars[i + 2] == 'i' && chars[i + 3] == 'd') {
                            if (chars[i + 4] == 'P' && chars[i + 5] == 'x') {
                                bidPx = extractBigDecimal(chars, i + 5);
                                i = lastExtractEnd;
                                continue;
                            } else if (chars[i + 4] == 'S' && chars[i + 5] == 'z') {
                                bidSz = extractBigDecimal(chars, i + 5);
                                i = lastExtractEnd;
                                continue;
                            }
                        }
                        break;
                    case 'a': // askPx or askSz
                        if (chars[i + 2] == 's' && chars[i + 3] == 'k') {
                            if (chars[i + 4] == 'P' && chars[i + 5] == 'x') {
                                askPx = extractBigDecimal(chars, i + 5);
                                i = lastExtractEnd;
                                continue;
                            } else if (chars[i + 4] == 'S' && chars[i + 5] == 'z') {
                                askSz = extractBigDecimal(chars, i + 5);
                                i = lastExtractEnd;
                                continue;
                            }
                        }
                        break;
                    case 'v': // vol24h
                        if (chars[i + 2] == 'o' && chars[i + 3] == 'l' && chars[i + 4] == '2' && chars[i + 5] == '4' && chars[i + 6] == 'h') {
                            vol24h = extractBigDecimal(chars, i + 6);
                            i = lastExtractEnd;
                            continue;
                        }
                        break;
                    case 'c': // changeUtc8
                        if (chars[i + 2] == 'h' && chars[i + 3] == 'a' && chars[i + 4] == 'n' && chars[i + 5] == 'g' && chars[i + 6] == 'e') {
                            changeUtc8 = extractBigDecimal(chars, i + 10);
                            i = lastExtractEnd;
                            continue;
                        }
                        break;
                    case 't': // ts
                        if (chars[i + 2] == 's') {
                            i = extractString(chars, i + 2);
                            int valStart = lastExtractStart;
                            int valEnd = lastExtractEnd;
                            if (valEnd > valStart) {
                                ts = parseLong(chars, valStart, valEnd);
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

        return new Ticker(
            Exchange.OKX,
            symbol,
            ts,
            System.nanoTime(),
            last != null ? last : BigDecimal.ZERO,
            bidPx != null ? bidPx : BigDecimal.ZERO,
            askPx != null ? askPx : BigDecimal.ZERO,
            bidSz != null ? bidSz : BigDecimal.ZERO,
            askSz != null ? askSz : BigDecimal.ZERO,
            vol24h != null ? vol24h : BigDecimal.ZERO,
            BigDecimal.ZERO,
            changeUtc8 != null ? changeUtc8 : BigDecimal.ZERO
        );
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
        outer:
        for (int i = start; i <= chars.length - str.length(); i++) {
            for (int j = 0; j < str.length(); j++) {
                if (chars[i + j] != str.charAt(j)) continue outer;
            }
            return i;
        }
        return -1;
    }

    private long parseLong(char[] chars, int start, int end) {
        long value = 0;
        boolean negative = false;
        int pos = start;
        if (chars[pos] == '-') {
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
