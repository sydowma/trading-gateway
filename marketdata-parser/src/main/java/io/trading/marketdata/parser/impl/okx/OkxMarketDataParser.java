package io.trading.marketdata.parser.impl.okx;

import io.trading.marketdata.parser.api.MarketDataParser;
import io.trading.marketdata.parser.api.OutputFormat;
import io.trading.marketdata.parser.api.ParseResult;
import io.trading.marketdata.parser.encoder.json.JsonEncoder;
import io.trading.marketdata.parser.encoder.sbe.SbeEncoder;
import io.trading.marketdata.parser.model.DataType;

/**
 * OKX market data parser implementing the MarketDataParser interface.
 * Delegates to the optimized FastCharOkxParsers and adds support for multiple output formats.
 */
public class OkxMarketDataParser implements MarketDataParser {

    private final FastCharOkxTickerParser tickerParser;
    private final FastCharOkxTradeParser tradeParser;
    private final FastCharOkxOrderBookParser orderBookParser;
    private final SbeEncoder sbeEncoder;
    private final JsonEncoder jsonEncoder;

    public OkxMarketDataParser() {
        this.tickerParser = new FastCharOkxTickerParser();
        this.tradeParser = new FastCharOkxTradeParser();
        this.orderBookParser = new FastCharOkxOrderBookParser();
        this.sbeEncoder = SbeEncoder.getInstance();
        this.jsonEncoder = JsonEncoder.getInstance();
    }

    @Override
    public ParseResult parse(String message, OutputFormat format) {
        long startTime = System.nanoTime();

        try {
            // Determine message type and parse accordingly
            DataType dataType = parseMessageType(message);

            if (dataType == DataType.UNKNOWN) {
                throw new IllegalArgumentException("Unknown message type for OKX");
            }

            // Parse to Java object first
            Object data = switch (dataType) {
                case TICKER -> tickerParser.parseTicker(message);
                case TRADES -> tradeParser.parseTrade(message);
                case ORDER_BOOK -> orderBookParser.parseOrderBook(message);
                default -> throw new IllegalArgumentException("Unsupported data type: " + dataType);
            };

            if (data == null) {
                throw new IllegalArgumentException("Failed to parse OKX message");
            }

            long parseTime = System.nanoTime() - startTime;

            // For JAVA format, return the object directly (zero overhead)
            if (format == OutputFormat.JAVA) {
                return new ParseResult(dataType, format, data, parseTime);
            }

            // For SBE format, encode the object
            if (format == OutputFormat.SBE) {
                byte[] sbeBytes;
                if (dataType == DataType.TICKER) {
                    sbeBytes = sbeEncoder.encodeTickerToSbe((io.trading.marketdata.parser.model.Ticker) data);
                } else if (dataType == DataType.TRADES) {
                    sbeBytes = sbeEncoder.encodeTradeToSbe((io.trading.marketdata.parser.model.Trade) data);
                } else if (dataType == DataType.ORDER_BOOK) {
                    sbeBytes = sbeEncoder.encodeOrderBookToSbe((io.trading.marketdata.parser.model.OrderBook) data);
                } else {
                    throw new IllegalArgumentException("Unsupported data type: " + dataType);
                }
                long totalParseTime = System.nanoTime() - startTime;
                return new ParseResult(dataType, format, sbeBytes, totalParseTime);
            }

            // For JSON format, encode the object
            if (format == OutputFormat.JSON) {
                String json;
                if (dataType == DataType.TICKER) {
                    json = jsonEncoder.encodeTickerToJson((io.trading.marketdata.parser.model.Ticker) data);
                } else if (dataType == DataType.TRADES) {
                    json = jsonEncoder.encodeTradeToJson((io.trading.marketdata.parser.model.Trade) data);
                } else if (dataType == DataType.ORDER_BOOK) {
                    json = jsonEncoder.encodeOrderBookToJson((io.trading.marketdata.parser.model.OrderBook) data);
                } else {
                    throw new IllegalArgumentException("Unsupported data type: " + dataType);
                }
                long totalParseTime = System.nanoTime() - startTime;
                return new ParseResult(dataType, format, json, totalParseTime);
            }

            throw new IllegalArgumentException("Unsupported output format: " + format);

        } catch (Exception e) {
            throw new RuntimeException("Failed to parse OKX message: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isTicker(String message) {
        return message.contains("\"arg\":") && message.contains("\"channel\":\"tickers\"");
    }

    @Override
    public boolean isTrade(String message) {
        return message.contains("\"arg\":") && message.contains("\"channel\":\"trades\"");
    }

    @Override
    public boolean isOrderBook(String message) {
        return message.contains("\"arg\":") &&
               (message.contains("\"channel\":\"books") || message.contains("\"channel\":\"books-l2"));
    }
}
