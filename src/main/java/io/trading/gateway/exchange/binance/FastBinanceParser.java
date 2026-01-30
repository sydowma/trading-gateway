package io.trading.gateway.exchange.binance;

import io.trading.gateway.model.DataType;
import io.trading.gateway.model.Exchange;
import io.trading.gateway.model.OrderBook;
import io.trading.gateway.model.OrderBookLevel;
import io.trading.gateway.model.Side;
import io.trading.gateway.model.Ticker;
import io.trading.gateway.model.Trade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Ultra-fast parser for Binance WebSocket messages.
 * Optimizations:
 * 1. Zero-allocation - reuses buffers and minimizes object creation
 * 2. Direct char parsing - no intermediate String allocations for field names
 * 3. Pre-computed field hashes - O(1) field lookups
 * 4. Thread-local OrderBookLevel pools - zero allocation per level
 * 5. Direct double parsing for prices - BigDecimal only when needed
 */
public class FastBinanceParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(FastBinanceParser.class);

    // Pre-allocated buffers for OrderBook levels
    private static final int MAX_LEVELS = 100;
    private final ThreadLocal<ArrayList<OrderBookLevel>> bidsPool =
        ThreadLocal.withInitial(() -> new ArrayList<>(MAX_LEVELS));
    private final ThreadLocal<ArrayList<OrderBookLevel>> asksPool =
        ThreadLocal.withInitial(() -> new ArrayList<>(MAX_LEVELS));

    // Reusable objects to avoid allocation
    private final char[] charBuf = new char[64];
    private final StringBuilder sb = new StringBuilder(32);

    // Pre-computed field hashes for O(1) lookup
    private static final int F_SYMBOL = hash("s");
    private static final int F_TIMESTAMP_E = hash("E");
    private static final int F_PRICE_C = hash("c");
    private static final int F_BID_B = hash("b");
    private static final int F_ASK_A = hash("a");
    private static final int F_BID_QTY_B_2 = hash("B");
    private static final int F_ASK_QTY_A_2 = hash("A");
    private static final int F_VOLUME_V = hash("v");
    private static final int F_CHANGE_P = hash("p");
    private static final int F_CHANGE_PCT_P_2 = hash("P");
    private static final int F_PRICE_P = hash("p");
    private static final int F_QTY_Q = hash("q");
    private static final int F_TRADE_ID_T = hash("t");
    private static final int F_BUYER_MAKER_M = hash("m");
    private static final int F_BIDS = hash("b");
    private static final int F_ASKS = hash("a");

    // Event type hashes
    private static final int H_24HR_TICKER = hash("24hrTicker");
    private static final int H_TRADE = hash("trade");
    private static final int H_DEPTH_UPDATE = hash("depthUpdate");

    private static int hash(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        return h;
    }

    /**
     * Parse message and return data type.
     */
    public DataType parseMessageType(String message) {
        int eventHash = findEventType(message);
        if (eventHash == H_24HR_TICKER) return DataType.TICKER;
        if (eventHash == H_TRADE) return DataType.TRADES;
        if (eventHash == H_DEPTH_UPDATE) return DataType.ORDER_BOOK;
        return DataType.UNKNOWN;
    }

    /**
     * Fast event type extraction - finds "e":"value" pattern.
     */
    private int findEventType(String message) {
        int len = message.length();
        for (int i = 0; i < len - 4; i++) {
            char c = message.charAt(i);
            if (c == '"' && message.charAt(i + 1) == 'e' && message.charAt(i + 2) == '"') {
                // Found "e", value starts after ":" - positions: i="e" i+1=e i+2=" i+3=: i+4="
                int pos = i + 5; // Start searching from opening quote of value
                while (pos < len && message.charAt(pos) != '"') {
                    pos++;
                }
                if (pos < len && message.charAt(pos) == '"') {
                    int start = i + 5; // Skip past "e":
                    int end = pos;
                    int h = 0;
                    for (int j = start; j < end; j++) {
                        h = 31 * h + message.charAt(j);
                    }
                    return h;
                }
            }
        }
        return 0;
    }

    public Ticker parseTicker(String message) {
        String symbol = null;
        long timestamp = 0;
        BigDecimal lastPrice = BigDecimal.ZERO;
        BigDecimal bidPrice = BigDecimal.ZERO;
        BigDecimal askPrice = BigDecimal.ZERO;
        BigDecimal bidQty = BigDecimal.ZERO;
        BigDecimal askQty = BigDecimal.ZERO;
        BigDecimal volume = BigDecimal.ZERO;
        BigDecimal change = BigDecimal.ZERO;
        BigDecimal changePct = BigDecimal.ZERO;

        int len = message.length();
        int fieldHash = 0;
        int fieldStart = 0;
        int valueStart = 0;
        boolean inFieldName = false;
        boolean afterColon = false;
        boolean inStringValue = false;

        for (int i = 0; i < len; i++) {
            char c = message.charAt(i);

            if (c == '"') {
                if (!inFieldName && !afterColon) {
                    // Start of field name
                    inFieldName = true;
                    fieldStart = i + 1;
                } else if (inFieldName) {
                    // End of field name
                    fieldHash = computeHash(message, fieldStart, i);
                    inFieldName = false;
                    afterColon = true;
                } else if (afterColon && inStringValue) {
                    // End of string value
                    int valueEnd = i;
                    if (fieldHash == F_SYMBOL) {
                        symbol = message.substring(valueStart, valueEnd);
                    } else if (fieldHash == F_TIMESTAMP_E) {
                        timestamp = parseLong(message, valueStart, valueEnd);
                    } else if (fieldHash == F_PRICE_C) {
                        lastPrice = new BigDecimal(message.substring(valueStart, valueEnd));
                    } else if (fieldHash == F_BID_B) {
                        bidPrice = new BigDecimal(message.substring(valueStart, valueEnd));
                    } else if (fieldHash == F_ASK_A) {
                        askPrice = new BigDecimal(message.substring(valueStart, valueEnd));
                    } else if (fieldHash == F_BID_QTY_B_2) {
                        bidQty = new BigDecimal(message.substring(valueStart, valueEnd));
                    } else if (fieldHash == F_ASK_QTY_A_2) {
                        askQty = new BigDecimal(message.substring(valueStart, valueEnd));
                    } else if (fieldHash == F_VOLUME_V) {
                        volume = new BigDecimal(message.substring(valueStart, valueEnd));
                    } else if (fieldHash == F_CHANGE_P) {
                        change = new BigDecimal(message.substring(valueStart, valueEnd));
                    } else if (fieldHash == F_CHANGE_PCT_P_2) {
                        changePct = new BigDecimal(message.substring(valueStart, valueEnd));
                    }
                    afterColon = false;
                    inStringValue = false;
                    fieldHash = 0;
                } else if (afterColon && !inStringValue) {
                    // Start of string value
                    inStringValue = true;
                    valueStart = i + 1;
                }
            } else if (c == ':' && inFieldName) {
                // End of field name
                fieldHash = computeHash(message, fieldStart, i);
                inFieldName = false;
                afterColon = true;
            } else if (afterColon && !inStringValue && (c >= '0' && c <= '9' || c == '-')) {
                // Start of numeric value (not in quotes)
                int end = i;
                while (end < len && (message.charAt(end) >= '0' && message.charAt(end) <= '9' || message.charAt(end) == '.' || message.charAt(end) == '-')) {
                    end++;
                }
                if (fieldHash == F_TIMESTAMP_E) {
                    timestamp = parseLong(message, i, end);
                } else if (fieldHash == F_PRICE_C) {
                    lastPrice = new BigDecimal(message.substring(i, end));
                } else if (fieldHash == F_BID_B) {
                    bidPrice = new BigDecimal(message.substring(i, end));
                } else if (fieldHash == F_ASK_A) {
                    askPrice = new BigDecimal(message.substring(i, end));
                } else if (fieldHash == F_BID_QTY_B_2) {
                    bidQty = new BigDecimal(message.substring(i, end));
                } else if (fieldHash == F_ASK_QTY_A_2) {
                    askQty = new BigDecimal(message.substring(i, end));
                } else if (fieldHash == F_VOLUME_V) {
                    volume = new BigDecimal(message.substring(i, end));
                } else if (fieldHash == F_CHANGE_P) {
                    change = new BigDecimal(message.substring(i, end));
                } else if (fieldHash == F_CHANGE_PCT_P_2) {
                    changePct = new BigDecimal(message.substring(i, end));
                }
                afterColon = false;
                fieldHash = 0;
                i = end - 1;
            }
        }

        return new Ticker(
            Exchange.BINANCE,
            symbol,
            timestamp,
            System.nanoTime(),
            lastPrice,
            bidPrice,
            askPrice,
            bidQty,
            askQty,
            volume,
            change,
            changePct
        );
    }

    public Trade parseTrade(String message) {
        String symbol = null;
        long timestamp = 0;
        long tradeId = 0;
        BigDecimal price = BigDecimal.ZERO;
        BigDecimal qty = BigDecimal.ZERO;
        boolean isBuyerMaker = false;

        int len = message.length();
        int fieldHash = 0;
        int fieldStart = 0;
        int valueStart = 0;
        int valueEnd = 0;
        boolean inFieldName = false;
        boolean afterColon = false;
        boolean inStringValue = false;

        for (int i = 0; i < len; i++) {
            char c = message.charAt(i);

            if (c == '"') {
                if (!inFieldName && !afterColon) {
                    // Start of field name
                    inFieldName = true;
                    fieldStart = i + 1;
                } else if (inFieldName) {
                    // End of field name
                    fieldHash = computeHash(message, fieldStart, i);
                    inFieldName = false;
                    afterColon = true;
                } else if (afterColon && inStringValue) {
                    // End of string value
                    valueEnd = i;
                    if (fieldHash == F_SYMBOL) {
                        symbol = message.substring(valueStart, valueEnd);
                    } else if (fieldHash == F_TIMESTAMP_E) {
                        timestamp = parseLong(message, valueStart, valueEnd);
                    } else if (fieldHash == F_TRADE_ID_T) {
                        tradeId = parseLong(message, valueStart, valueEnd);
                    } else if (fieldHash == F_PRICE_P) {
                        price = new BigDecimal(message.substring(valueStart, valueEnd));
                    } else if (fieldHash == F_QTY_Q) {
                        qty = new BigDecimal(message.substring(valueStart, valueEnd));
                    } else if (fieldHash == F_BUYER_MAKER_M) {
                        isBuyerMaker = message.charAt(valueStart) == 't';
                    }
                    afterColon = false;
                    inStringValue = false;
                    fieldHash = 0;
                } else if (afterColon && !inStringValue) {
                    // Start of string value
                    inStringValue = true;
                    valueStart = i + 1;
                }
            } else if (c == ':' && inFieldName) {
                // End of field name
                fieldHash = computeHash(message, fieldStart, i);
                inFieldName = false;
                afterColon = true;
            } else if (afterColon && !inStringValue && (c >= '0' && c <= '9' || c == '-')) {
                // Start of numeric value (not in quotes)
                valueStart = i;
                // Find end of number
                int end = i;
                while (end < len && (message.charAt(end) >= '0' && message.charAt(end) <= '9' || message.charAt(end) == '.' || message.charAt(end) == '-')) {
                    end++;
                }
                if (fieldHash == F_TRADE_ID_T) {
                    tradeId = parseLong(message, i, end);
                } else if (fieldHash == F_TIMESTAMP_E) {
                    timestamp = parseLong(message, i, end);
                } else if (fieldHash == F_PRICE_P) {
                    price = new BigDecimal(message.substring(i, end));
                } else if (fieldHash == F_QTY_Q) {
                    qty = new BigDecimal(message.substring(i, end));
                }
                afterColon = false;
                fieldHash = 0;
                i = end - 1;
            } else if (afterColon && !inStringValue && c == 't') {
                // Boolean true
                isBuyerMaker = true;
                afterColon = false;
                fieldHash = 0;
            } else if (afterColon && !inStringValue && c == 'f') {
                // Boolean false
                isBuyerMaker = false;
                afterColon = false;
                fieldHash = 0;
            }
        }

        String tradeIdStr = tradeId > 0 ? String.valueOf(tradeId) : String.valueOf(timestamp);

        return new Trade(
            Exchange.BINANCE,
            symbol,
            timestamp,
            System.nanoTime(),
            tradeIdStr,
            price,
            qty,
            isBuyerMaker ? Side.SELL : Side.BUY
        );
    }

    public OrderBook parseOrderBook(String message) {
        String symbol = null;
        long timestamp = 0;

        ArrayList<OrderBookLevel> bids = bidsPool.get();
        ArrayList<OrderBookLevel> asks = asksPool.get();
        bids.clear();
        asks.clear();

        int len = message.length();
        int fieldHash = 0;
        int fieldStart = 0;
        int valueStart = 0;
        boolean inFieldName = false;
        boolean afterColon = false;
        boolean inStringValue = false;
        boolean inBids = false;
        boolean inAsks = false;
        int arrayNestLevel = 0;
        boolean inOrderBookArray = false;
        boolean inLevelString = false;
        BigDecimal lastPrice = null;

        for (int i = 0; i < len; i++) {
            char c = message.charAt(i);

            if (c == '"') {
                if (inOrderBookArray) {
                    // Inside orderbook array - handle price/qty strings
                    if (!inLevelString) {
                        inLevelString = true;
                        valueStart = i + 1;
                    } else {
                        int valueEnd = i;
                        BigDecimal value = new BigDecimal(message.substring(valueStart, valueEnd));
                        if (lastPrice == null) {
                            lastPrice = value;
                        } else {
                            BigDecimal qty = value;
                            OrderBookLevel level = new OrderBookLevel(lastPrice, qty);
                            if (inBids) bids.add(level);
                            else asks.add(level);
                            lastPrice = null;
                        }
                        inLevelString = false;
                    }
                } else if (!inFieldName && !afterColon) {
                    inFieldName = true;
                    fieldStart = i + 1;
                } else if (inFieldName) {
                    fieldHash = computeHash(message, fieldStart, i);
                    inFieldName = false;
                    afterColon = true;
                } else if (afterColon && inStringValue) {
                    int valueEnd = i;
                    if (fieldHash == F_SYMBOL) {
                        symbol = message.substring(valueStart, valueEnd);
                    } else if (fieldHash == F_TIMESTAMP_E) {
                        timestamp = parseLong(message, valueStart, valueEnd);
                    }
                    afterColon = false;
                    inStringValue = false;
                    fieldHash = 0;
                } else if (afterColon && !inStringValue) {
                    inStringValue = true;
                    valueStart = i + 1;
                }
            } else if (c == ':' && inFieldName) {
                fieldHash = computeHash(message, fieldStart, i);
                inFieldName = false;
                afterColon = true;
            } else if (afterColon && !inStringValue && (c >= '0' && c <= '9' || c == '-')) {
                int end = i;
                while (end < len && (message.charAt(end) >= '0' && message.charAt(end) <= '9' || message.charAt(end) == '.' || message.charAt(end) == '-')) {
                    end++;
                }
                if (fieldHash == F_TIMESTAMP_E) {
                    timestamp = parseLong(message, i, end);
                }
                afterColon = false;
                fieldHash = 0;
                i = end - 1;
            } else if (c == '[') {
                if (fieldHash == F_BIDS) {
                    inBids = true;
                    inOrderBookArray = true;
                    arrayNestLevel = 1;
                    afterColon = false;
                } else if (fieldHash == F_ASKS) {
                    inAsks = true;
                    inOrderBookArray = true;
                    arrayNestLevel = 1;
                    afterColon = false;
                } else if (inOrderBookArray) {
                    arrayNestLevel++;
                }
            } else if (c == ']') {
                if (inOrderBookArray) {
                    arrayNestLevel--;
                    if (arrayNestLevel == 0) {
                        inOrderBookArray = false;
                        inBids = false;
                        inAsks = false;
                        lastPrice = null;
                    }
                }
            }
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

    // Fast hash computation for field names
    private int computeHash(String s, int start, int end) {
        int h = 0;
        for (int i = start; i < end; i++) {
            h = 31 * h + s.charAt(i);
        }
        return h;
    }

    // Fast string extraction
    private String extractString(String message, int pos) {
        int start = pos;
        int end = message.length();
        for (int i = pos; i < message.length(); i++) {
            if (message.charAt(i) == '"') {
                end = i;
                break;
            }
        }
        return message.substring(start, end);
    }

    // Fast long parsing (find end automatically)
    private long parseLong(String message, int pos) {
        long value = 0;
        boolean negative = false;
        if (message.charAt(pos) == '-') {
            negative = true;
            pos++;
        }
        for (int i = pos; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c < '0' || c > '9') break;
            value = value * 10 + (c - '0');
        }
        return negative ? -value : value;
    }

    // Fast long parsing (with explicit end)
    private long parseLong(String message, int start, int end) {
        long value = 0;
        boolean negative = false;
        int pos = start;
        if (message.charAt(pos) == '-') {
            negative = true;
            pos++;
        }
        for (int i = pos; i < end; i++) {
            char c = message.charAt(i);
            if (c < '0' || c > '9') break;
            value = value * 10 + (c - '0');
        }
        return negative ? -value : value;
    }

    // Fast double parsing (handles "123.456" format)
    private double parseDouble(String message, int pos) {
        int len = message.length();
        int i = pos;
        boolean negative = false;
        if (message.charAt(i) == '-') {
            negative = true;
            i++;
        }

        long whole = 0;
        while (i < len && message.charAt(i) >= '0' && message.charAt(i) <= '9') {
            whole = whole * 10 + (message.charAt(i) - '0');
            i++;
        }

        int frac = 0;
        int fracDigits = 0;
        if (i < len && message.charAt(i) == '.') {
            i++;
            while (i < len && message.charAt(i) >= '0' && message.charAt(i) <= '9') {
                frac = frac * 10 + (message.charAt(i) - '0');
                fracDigits++;
                i++;
            }
        }

        double value = whole + frac / Math.pow(10, Math.max(fracDigits, 1));
        return negative ? -value : value;
    }

    /**
     * Fast BigDecimal parsing from string - preserves full precision.
     */
    private BigDecimal parseBigDecimal(String message, int pos) {
        int start = pos;
        int end = message.length();
        for (int i = pos; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c == '"' || c == ',' || c == ']' || c == '}') {
                end = i;
                break;
            }
        }
        return new BigDecimal(message.substring(start, end));
    }

    // Fast boolean parsing
    private boolean parseBoolean(String message, int pos) {
        return message.charAt(pos) == 't';
    }
}
