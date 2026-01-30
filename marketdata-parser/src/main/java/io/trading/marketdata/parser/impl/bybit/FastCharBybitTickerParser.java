package io.trading.marketdata.parser.impl.bybit;

import io.trading.marketdata.parser.model.Exchange;
import io.trading.marketdata.parser.model.Ticker;

import java.math.BigDecimal;

/**
 * Fast Bybit ticker parser using char[] directly with BigDecimal.
 */
public class FastCharBybitTickerParser {

    public Ticker parseTicker(String message) {
        char[] chars = message.toCharArray();
        int len = chars.length;

        // Find "data":{
        int dataIdx = indexOf(chars, 0, "data");
        if (dataIdx < 0) return null;

        // Skip to object content
        int objStart = dataIdx + 4;
        while (objStart < len && chars[objStart] != ':') objStart++;
        while (objStart < len && chars[objStart] != '{') objStart++;
        if (objStart >= len) return null;

        // Parse fields
        String symbol = null;
        long ts = 0;
        BigDecimal lastPrice = null;
        BigDecimal bid1Price = null;
        BigDecimal ask1Price = null;
        BigDecimal bid1Qty = null;
        BigDecimal ask1Qty = null;
        BigDecimal volume24h = null;
        BigDecimal price24hPcnt = null;

        int i = objStart;
        while (i < len - 10) {
            if (chars[i] == '"' && chars[i + 1] != '"') {
                // Quick field detection using first char
                switch (chars[i + 1]) {
                    case 's': // symbol
                        if (chars[i + 2] == 'y' && chars[i + 3] == 'm' && chars[i + 4] == 'b' && chars[i + 5] == 'o' && chars[i + 6] == 'l') {
                            i = extractString(chars, i + 6);
                            int valStart = lastExtractStart;
                            int valEnd = lastExtractEnd;
                            if (valEnd > valStart) {
                                symbol = new String(chars, valStart, valEnd - valStart);
                            }
                            continue;
                        }
                        break;
                    case 'l': // lastPrice
                        if (i + 9 < len && chars[i + 2] == 'a' && chars[i + 3] == 's' && chars[i + 4] == 't' &&
                            chars[i + 5] == 'P' && chars[i + 6] == 'r' && chars[i + 7] == 'i' && chars[i + 8] == 'c' && chars[i + 9] == 'e') {
                            lastPrice = extractBigDecimal(chars, i + 9);
                            i = lastExtractEnd;
                            continue;
                        }
                        break;
                    case 'b': // bid1Price or bid1Qty
                        if (chars[i + 2] == 'i' && chars[i + 3] == 'd') {
                            if (i + 9 < len && chars[i + 4] == '1' && chars[i + 5] == 'P' &&
                                chars[i + 6] == 'r' && chars[i + 7] == 'i' && chars[i + 8] == 'c' && chars[i + 9] == 'e') {
                                bid1Price = extractBigDecimal(chars, i + 9);
                                i = lastExtractEnd;
                                continue;
                            } else if (chars[i + 4] == '1' && chars[i + 5] == 'Q' && chars[i + 6] == 't' && chars[i + 7] == 'y') {
                                bid1Qty = extractBigDecimal(chars, i + 7);
                                i = lastExtractEnd;
                                continue;
                            }
                        }
                        break;
                    case 'a': // ask1Price or ask1Qty
                        if (chars[i + 2] == 's' && chars[i + 3] == 'k') {
                            if (i + 9 < len && chars[i + 4] == '1' && chars[i + 5] == 'P' &&
                                chars[i + 6] == 'r' && chars[i + 7] == 'i' && chars[i + 8] == 'c' && chars[i + 9] == 'e') {
                                ask1Price = extractBigDecimal(chars, i + 9);
                                i = lastExtractEnd;
                                continue;
                            } else if (chars[i + 4] == '1' && chars[i + 5] == 'Q' && chars[i + 6] == 't' && chars[i + 7] == 'y') {
                                ask1Qty = extractBigDecimal(chars, i + 7);
                                i = lastExtractEnd;
                                continue;
                            }
                        }
                        break;
                    case 'v': // volume24h
                        if (i + 9 < len && chars[i + 2] == 'o' && chars[i + 3] == 'l' && chars[i + 4] == 'u' &&
                            chars[i + 5] == 'm' && chars[i + 6] == 'e' && chars[i + 7] == '2' && chars[i + 8] == '4' && chars[i + 9] == 'h') {
                            volume24h = extractBigDecimal(chars, i + 9);
                            i = lastExtractEnd;
                            continue;
                        }
                        break;
                    case 'p': // price24hPcnt
                        if (i + 12 < len && chars[i + 2] == 'r' && chars[i + 3] == 'i' && chars[i + 4] == 'c' &&
                            chars[i + 5] == 'e' && chars[i + 6] == '2' && chars[i + 7] == '4' && chars[i + 8] == 'h' &&
                            chars[i + 9] == 'P' && chars[i + 10] == 'c' && chars[i + 11] == 'n' && chars[i + 12] == 't') {
                            price24hPcnt = extractBigDecimal(chars, i + 12);
                            i = lastExtractEnd;
                            continue;
                        }
                        break;
                    case 't': // ts (timestamp at top level)
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
            Exchange.BYBIT,
            symbol,
            ts,
            System.nanoTime(),
            lastPrice != null ? lastPrice : BigDecimal.ZERO,
            bid1Price != null ? bid1Price : BigDecimal.ZERO,
            ask1Price != null ? ask1Price : BigDecimal.ZERO,
            bid1Qty != null ? bid1Qty : BigDecimal.ZERO,
            ask1Qty != null ? ask1Qty : BigDecimal.ZERO,
            volume24h != null ? volume24h : BigDecimal.ZERO,
            BigDecimal.ZERO,
            price24hPcnt != null ? price24hPcnt : BigDecimal.ZERO
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
