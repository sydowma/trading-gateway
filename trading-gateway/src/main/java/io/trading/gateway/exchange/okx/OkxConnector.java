package io.trading.gateway.exchange.okx;

import io.trading.gateway.core.ProcessingTimer;
import io.trading.gateway.exchange.ExchangeConnector;
import io.trading.gateway.exchange.ExchangeMessageHandler;
import io.trading.marketdata.parser.api.MarketDataParser;
import io.trading.marketdata.parser.api.OutputFormat;
import io.trading.marketdata.parser.api.ParseResult;
import io.trading.marketdata.parser.impl.okx.OkxMarketDataParser;
import io.trading.marketdata.parser.model.DataType;
import io.trading.marketdata.parser.model.Exchange;
import io.trading.marketdata.parser.model.OrderBook;
import io.trading.marketdata.parser.model.Ticker;
import io.trading.marketdata.parser.model.Trade;
import io.trading.gateway.netty.ReconnectHandler;
import io.trading.gateway.netty.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OKX WebSocket connector.
 * Connects to wss://ws.okx.com:8443/ws/v5/public
 */
public class OkxConnector implements ExchangeConnector {

    private static final Logger LOGGER = LoggerFactory.getLogger(OkxConnector.class);

    private static final String WS_URL = "wss://ws.okx.com:8443/ws/v5/public";

    // Use unified parser with JAVA format (zero overhead)
    private final MarketDataParser parser = new OkxMarketDataParser();
    private final ProcessingTimer processingTimer = new ProcessingTimer();
    private final AtomicLong messageCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    // Separate WebSocket connection for each data type
    private final Map<DataType, WebSocketClient> clients = new ConcurrentHashMap<>();
    private final Map<DataType, ReconnectHandler> reconnectHandlers = new ConcurrentHashMap<>();
    private final Map<DataType, Boolean> connectedStates = new ConcurrentHashMap<>();
    private ExchangeMessageHandler messageHandler;

    @Override
    public Exchange getExchange() {
        return Exchange.OKX;
    }

    @Override
    public void connect() {
        // Create separate connection for each data type
        for (DataType dataType : DataType.values()) {
            if (dataType == DataType.UNKNOWN) continue;

            try {
                URI uri = URI.create(WS_URL);
                String channelName = "OKX-" + dataType.name();

                ReconnectHandler reconnectHandler = new ReconnectHandler(
                    channelName,
                    10,
                    () -> doConnect(dataType)
                );
                reconnectHandler.start();

                WebSocketClient client = new WebSocketClient(
                    uri,
                    channelName,
                    msg -> onMessage(msg, dataType),
                    err -> onError(err, dataType),
                    () -> onConnected(dataType),
                    () -> onDisconnected(dataType),
                    false  // Disable compression for better compatibility
                );

                clients.put(dataType, client);
                reconnectHandlers.put(dataType, reconnectHandler);
                connectedStates.put(dataType, false);

                doConnect(dataType);

            } catch (Exception e) {
                LOGGER.error("[OKX-{}] Failed to initialize connector", dataType, e);
            }
        }
    }

    @Override
    public void disconnect() {
        for (DataType dataType : clients.keySet()) {
            ReconnectHandler handler = reconnectHandlers.get(dataType);
            if (handler != null) {
                handler.stop();
            }

            WebSocketClient client = clients.get(dataType);
            if (client != null) {
                client.close();
            }

            connectedStates.put(dataType, false);
        }

        clients.clear();
        reconnectHandlers.clear();
        connectedStates.clear();

        LOGGER.info("[OKX] Disconnected all connections");
    }

    @Override
    public boolean isConnected() {
        // Consider connected if at least one connection is active
        return connectedStates.values().stream().anyMatch(connected -> connected);
    }

    @Override
    public void subscribe(Set<String> symbols, Set<DataType> dataTypes) {
        // Subscribe to each data type on its dedicated connection
        for (DataType dataType : dataTypes) {
            WebSocketClient client = clients.get(dataType);
            if (client == null) {
                LOGGER.warn("[OKX-{}] No client for data type", dataType);
                continue;
            }

            // Build subscription args for all symbols of this data type
            java.util.List<String> args = new java.util.ArrayList<>();

            for (String symbol : symbols) {
                String okxSymbol = convertSymbolToOkxFormat(symbol);

                if (dataType == DataType.TICKER) {
                    args.add(String.format("{\"channel\":\"tickers\",\"instId\":\"%s\"}", okxSymbol));
                } else if (dataType == DataType.TRADES) {
                    args.add(String.format("{\"channel\":\"trades\",\"instId\":\"%s\"}", okxSymbol));
                } else if (dataType == DataType.ORDER_BOOK) {
                    // Use standard books channel (full order book)
                    args.add(String.format("{\"channel\":\"books\",\"instId\":\"%s\"}", okxSymbol));
                }
            }

            if (!args.isEmpty()) {
                // Send single subscription with all symbols for this data type
                String subscribeMsg = String.format(
                    "{\"op\":\"subscribe\",\"args\":[%s]}",
                    String.join(",", args)
                );
                client.send(subscribeMsg);
                LOGGER.info("[OKX-{}] Subscribed to {}: {} - Message: {}", dataType, dataType, symbols, subscribeMsg);
            }
        }
    }

    @Override
    public void setMessageHandler(ExchangeMessageHandler handler) {
        this.messageHandler = handler;
    }

    @Override
    public long getMessageCount() {
        return messageCount.get();
    }

    @Override
    public long getErrorCount() {
        return errorCount.get();
    }

    @Override
    public ProcessingTimer getProcessingTimer() {
        return processingTimer;
    }

    @Override
    public void close() {
        disconnect();
    }

    private void doConnect(DataType dataType) {
        WebSocketClient client = clients.get(dataType);
        if (client != null) {
            client.connect();
        }
    }

    private void onConnected(DataType dataType) {
        connectedStates.put(dataType, true);
        ReconnectHandler handler = reconnectHandlers.get(dataType);
        if (handler != null) {
            handler.reset();
        }
        LOGGER.info("[OKX-{}] Connected", dataType);
    }

    private void onDisconnected(DataType dataType) {
        connectedStates.put(dataType, false);
        LOGGER.warn("[OKX-{}] Disconnected, scheduling reconnect...", dataType);
        ReconnectHandler handler = reconnectHandlers.get(dataType);
        if (handler != null) {
            handler.scheduleReconnect();
        }
    }

    private void onMessage(String message, DataType sourceDataType) {
        messageCount.incrementAndGet();

        ProcessingTimer.TimingContext totalTimer = processingTimer.start();

        try {
            // Parse and dispatch message using new parser API
            ParseResult result = parser.parse(message, OutputFormat.JAVA);
            DataType actualDataType = result.getDataType();

            if (actualDataType == DataType.TICKER) {
                Ticker ticker = result.getAsTicker();

                if (messageHandler != null) {
                    messageHandler.onTicker(ticker);
                }

                processingTimer.record("OKX", "TICKER", result.getParseTimeNanos());
            } else if (actualDataType == DataType.TRADES) {
                Trade trade = result.getAsTrade();

                if (messageHandler != null) {
                    messageHandler.onTrade(trade);
                }

                processingTimer.record("OKX", "TRADE", result.getParseTimeNanos());
            } else if (actualDataType == DataType.ORDER_BOOK) {
                OrderBook orderBook = result.getAsOrderBook();

                if (messageHandler != null) {
                    messageHandler.onOrderBook(orderBook);
                }

                processingTimer.record("OKX", "ORDER_BOOK", result.getParseTimeNanos());
            }

            totalTimer.stop();
        } catch (Exception e) {
            errorCount.incrementAndGet();
            LOGGER.error("[OKX-{}] Failed to parse message", sourceDataType, e);
        }
    }

    private void onError(Throwable error, DataType dataType) {
        errorCount.incrementAndGet();
        LOGGER.error("[OKX-{}] WebSocket error", dataType, error);
    }

    /**
     * Converts symbol format (e.g., BTCUSDT -> BTC-USDT for OKX).
     * OKX uses dash separator for spot pairs (e.g., BTC-USDT, ETH-USDT).
     */
    private String convertSymbolToOkxFormat(String symbol) {
        // Handle common quote currencies
        if (symbol.endsWith("USDT") || symbol.endsWith("USDC") ||
            symbol.endsWith("BTC") || symbol.endsWith("ETH") ||
            symbol.endsWith("SOL") || symbol.endsWith("USD")) {

            // Try to find the split point by checking common base currencies
            String[] quoteCurrencies = {"USDT", "USDC", "BTC", "ETH", "SOL", "USD"};
            for (String quote : quoteCurrencies) {
                if (symbol.endsWith(quote) && symbol.length() > quote.length()) {
                    String base = symbol.substring(0, symbol.length() - quote.length());
                    if (!base.isEmpty()) {
                        return base + "-" + quote;
                    }
                }
            }
        }

        // Default: try to split before the last 4 characters
        if (symbol.length() > 4) {
            return symbol.substring(0, symbol.length() - 4) + "-" + symbol.substring(symbol.length() - 4);
        }

        return symbol;
    }
}
