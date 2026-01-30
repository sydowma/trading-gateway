package io.trading.marketdata.parser.impl.okx;

import io.trading.marketdata.parser.model.Exchange;
import io.trading.marketdata.parser.model.Side;
import io.trading.marketdata.parser.model.Trade;

import java.math.BigDecimal;

/**
 * Fast OKX trade parser using char[] directly.
 */
public class FastCharOkxTradeParser {

    public Trade parseTrade(String message) {
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

        String symbol = null;
        long tradeId = 0;
        Side side = null;
        BigDecimal price = null;
        BigDecimal qty = null;
        long ts = 0;

        int i = objStart;
        while (i < len - 7) {
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
                            continue;
                        }
                        break;
                    case 't': // tradeId or ts
                        if (chars[i + 2] == 'r' && chars[i + 3] == 'a' && chars[i + 4] == 'd' &&
                            chars[i + 5] == 'e' && chars[i + 6] == 'I' && chars[i + 7] == 'd') {
                            // tradeId
                            i = extractString(chars, i + 7);
                            int start = lastExtractStart;
                            int end = lastExtractEnd;
                            if (end > start) {
                                tradeId = parseLong(chars, start, end);
                            }
                            i = lastExtractEnd;
                            continue;
                        } else if (chars[i + 2] == 's') {
                            // ts
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
                    case 's': // side
                        if (chars[i + 2] == 'i' && chars[i + 3] == 'd' && chars[i + 4] == 'e') {
                            i = extractString(chars, i + 4);
                            int start = lastExtractStart;
                            int end = lastExtractEnd;
                            if (end > start) {
                                String sideStr = new String(chars, start, end - start);
                                side = "buy".equals(sideStr) ? Side.BUY : Side.SELL;
                            }
                            i = lastExtractEnd;
                            continue;
                        }
                        break;
                    case 'p': // px (price)
                        if (chars[i + 2] == 'x') {
                            price = extractBigDecimal(chars, i + 2);
                            i = lastExtractEnd;
                            continue;
                        }
                        break;
                    case 'q': // qty (sz in OKX)
                        break;
                    case 'S': // Sz (quantity)
                        if (chars[i + 2] == 'z') {
                            qty = extractBigDecimal(chars, i + 2);
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

        return new Trade(
            Exchange.OKX,
            symbol,
            ts,
            System.nanoTime(),
            String.valueOf(tradeId),
            price != null ? price : BigDecimal.ZERO,
            qty != null ? qty : BigDecimal.ZERO,
            side != null ? side : Side.BUY
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
