package io.trading.gateway.exchange.binance;

import io.trading.gateway.core.ProcessingTimer;
import io.trading.gateway.exchange.ExchangeConnector;
import io.trading.gateway.exchange.ExchangeMessageHandler;
import io.trading.marketdata.parser.api.MarketDataParser;
import io.trading.marketdata.parser.api.OutputFormat;
import io.trading.marketdata.parser.api.ParseResult;
import io.trading.marketdata.parser.impl.binance.BinanceMarketDataParser;
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
import java.util.stream.Collectors;

/**
 * Binance WebSocket connector.
 * Connects to wss://stream.binance.com:9443/ws
 */
public class BinanceConnector implements ExchangeConnector {

    private static final Logger LOGGER = LoggerFactory.getLogger(BinanceConnector.class);

    // Individual streams URL
    private static final String WS_URL = "wss://stream.binance.com:9443/ws";
    // Combined streams URL (more efficient for multiple subscriptions)
    private static final String WS_COMBINED_URL = "wss://stream.binance.com:9443/stream";

    // Use fast parser for better performance with JAVA output format (zero overhead)
    private final MarketDataParser parser = new BinanceMarketDataParser();
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
        return Exchange.BINANCE;
    }

    @Override
    public void connect() {
        // Create separate connection for each data type
        for (DataType dataType : DataType.values()) {
            if (dataType == DataType.UNKNOWN) continue;

            try {
                // Use raw WebSocket URL for dynamic subscriptions
                URI uri = URI.create(WS_URL);
                String channelName = "Binance-" + dataType.name();

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
                    true  // Enable compression for Binance
                );

                clients.put(dataType, client);
                reconnectHandlers.put(dataType, reconnectHandler);
                connectedStates.put(dataType, false);

                doConnect(dataType);

            } catch (Exception e) {
                LOGGER.error("[Binance-{}] Failed to initialize connector", dataType, e);
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

        LOGGER.info("[Binance] Disconnected all connections");
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
            if (client == null || !connectedStates.getOrDefault(dataType, false)) {
                LOGGER.warn("[Binance-{}] Cannot subscribe, connection not ready", dataType);
                continue;
            }

            StringBuilder streamsBuilder = new StringBuilder();
            for (String symbol : symbols) {
                String lowerSymbol = symbol.toLowerCase();

                if (dataType == DataType.TICKER) {
                    if (streamsBuilder.length() > 0) streamsBuilder.append("/");
                    streamsBuilder.append(lowerSymbol).append("@ticker");
                } else if (dataType == DataType.TRADES) {
                    if (streamsBuilder.length() > 0) streamsBuilder.append("/");
                    streamsBuilder.append(lowerSymbol).append("@trade");
                } else if (dataType == DataType.ORDER_BOOK) {
                    if (streamsBuilder.length() > 0) streamsBuilder.append("/");
                    streamsBuilder.append(lowerSymbol).append("@depth");
                }
            }

            // Send subscription request for this data type
            String subscribeMsg = String.format(
                "{\"method\":\"SUBSCRIBE\",\"params\":[\"%s\"],\"id\":%d}",
                streamsBuilder.toString().replace("/", "\",\""),
                System.currentTimeMillis()
            );
            client.send(subscribeMsg);
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
        LOGGER.info("[Binance-{}] Connected", dataType);
    }

    private void onDisconnected(DataType dataType) {
        connectedStates.put(dataType, false);
        LOGGER.warn("[Binance-{}] Disconnected, scheduling reconnect...", dataType);
        ReconnectHandler handler = reconnectHandlers.get(dataType);
        if (handler != null) {
            handler.scheduleReconnect();
        }
    }

    private void onMessage(String message, DataType sourceDataType) {
        messageCount.incrementAndGet();

        ProcessingTimer.TimingContext totalTimer = processingTimer.start();

        try {
            // Fast check for subscription confirmation messages: {"result":...}
            // This is O(1) - just check first 10 characters instead of scanning entire message
            int len = message.length();
            if (len > 10) {
                char c0 = message.charAt(0);
                char c1 = len > 1 ? message.charAt(1) : 0;
                char c2 = len > 2 ? message.charAt(2) : 0;
                // Check if starts with {"r (which would be {"result":)
                if (c0 == '{' && c1 == '"' && c2 == 'r') {
                    totalTimer.stop();
                    return;
                }
            }

            // Parse and dispatch message using new parser API with JAVA format (zero overhead)
            ParseResult result = parser.parse(message, OutputFormat.JAVA);
            DataType actualDataType = result.getDataType();

            if (actualDataType == DataType.TICKER) {
                Ticker ticker = result.getAsTicker();

                if (messageHandler != null) {
                    messageHandler.onTicker(ticker);
                }

                processingTimer.record("BINANCE", "TICKER", result.getParseTimeNanos());
            } else if (actualDataType == DataType.TRADES) {
                Trade trade = result.getAsTrade();

                if (messageHandler != null) {
                    messageHandler.onTrade(trade);
                }

                processingTimer.record("BINANCE", "TRADE", result.getParseTimeNanos());
            } else if (actualDataType == DataType.ORDER_BOOK) {
                OrderBook orderBook = result.getAsOrderBook();

                if (messageHandler != null) {
                    messageHandler.onOrderBook(orderBook);
                }

                processingTimer.record("BINANCE", "ORDER_BOOK", result.getParseTimeNanos());
            }

            totalTimer.stop();
        } catch (Exception e) {
            errorCount.incrementAndGet();
            LOGGER.error("[Binance-{}] Failed to parse message", sourceDataType, e);
        }
    }

    private void onError(Throwable error, DataType dataType) {
        errorCount.incrementAndGet();
        LOGGER.error("[Binance-{}] WebSocket error", dataType, error);
    }
}
