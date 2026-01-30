package io.trading.marketdata.parser.impl.binance;

import io.trading.marketdata.parser.api.MarketDataParser;
import io.trading.marketdata.parser.api.OutputFormat;
import io.trading.marketdata.parser.api.ParseResult;
import io.trading.marketdata.parser.encoder.MessageEncoder;
import io.trading.marketdata.parser.encoder.json.JsonEncoder;
import io.trading.marketdata.parser.encoder.sbe.SbeEncoder;
import io.trading.marketdata.parser.model.DataType;

/**
 * Binance market data parser implementing the MarketDataParser interface.
 * Delegates to the optimized FastBinanceParser and adds support for multiple output formats.
 */
public class BinanceMarketDataParser implements MarketDataParser {

    private final FastBinanceParser parser;
    private final SbeEncoder sbeEncoder;
    private final JsonEncoder jsonEncoder;

    public BinanceMarketDataParser() {
        this.parser = new FastBinanceParser();
        this.sbeEncoder = SbeEncoder.getInstance();
        this.jsonEncoder = JsonEncoder.getInstance();
    }

    @Override
    public ParseResult parse(String message, OutputFormat format) {
        long startTime = System.nanoTime();

        try {
            // Determine message type
            DataType dataType = parser.parseMessageType(message);

            // Skip subscription confirmation messages
            if (dataType == DataType.UNKNOWN) {
                // Return a special result indicating to skip this message
                return new ParseResult(DataType.UNKNOWN, format, null, System.nanoTime() - startTime);
            }

            // Parse to Java object first
            Object data = switch (dataType) {
                case TICKER -> parser.parseTicker(message);
                case TRADES -> parser.parseTrade(message);
                case ORDER_BOOK -> parser.parseOrderBook(message);
                default -> throw new IllegalArgumentException("Unsupported data type: " + dataType);
            };

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
            throw new RuntimeException("Failed to parse Binance message: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isTicker(String message) {
        return parser.isTicker(message);
    }

    @Override
    public boolean isTrade(String message) {
        return parser.isTrade(message);
    }

    @Override
    public boolean isOrderBook(String message) {
        return parser.isOrderBook(message);
    }
}
