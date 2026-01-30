package io.trading.gateway.exchange.bybit;

import io.trading.gateway.core.ProcessingTimer;
import io.trading.gateway.exchange.ExchangeConnector;
import io.trading.gateway.exchange.ExchangeMessageHandler;
import io.trading.marketdata.parser.api.MarketDataParser;
import io.trading.marketdata.parser.api.OutputFormat;
import io.trading.marketdata.parser.api.ParseResult;
import io.trading.marketdata.parser.impl.bybit.BybitMarketDataParser;
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
 * Bybit WebSocket connector.
 * Connects to wss://stream.bybit.com/v5/public/spot
 */
public class BybitConnector implements ExchangeConnector {

    private static final Logger LOGGER = LoggerFactory.getLogger(BybitConnector.class);

    private static final String WS_URL = "wss://stream.bybit.com/v5/public/spot";

    // Use unified parser with JAVA format (zero overhead)
    private final MarketDataParser parser = new BybitMarketDataParser();
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
        return Exchange.BYBIT;
    }

    @Override
    public void connect() {
        // Create separate connection for each data type
        for (DataType dataType : DataType.values()) {
            if (dataType == DataType.UNKNOWN) continue;

            try {
                URI uri = URI.create(WS_URL);
                String channelName = "Bybit-" + dataType.name();

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
                    false  // Disable compression - Bybit has non-standard permessage-deflate implementation
                );

                clients.put(dataType, client);
                reconnectHandlers.put(dataType, reconnectHandler);
                connectedStates.put(dataType, false);

                doConnect(dataType);

            } catch (Exception e) {
                LOGGER.error("[Bybit-{}] Failed to initialize connector", dataType, e);
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

        LOGGER.info("[Bybit] Disconnected all connections");
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
                LOGGER.warn("[Bybit-{}] No client for data type", dataType);
                continue;
            }

            // Build subscription args for all symbols of this data type
            java.util.List<String> args = new java.util.ArrayList<>();

            for (String symbol : symbols) {
                if (dataType == DataType.TICKER) {
                    args.add("tickers." + symbol);
                } else if (dataType == DataType.TRADES) {
                    args.add("publicTrade." + symbol);
                } else if (dataType == DataType.ORDER_BOOK) {
                    args.add("orderbook.1." + symbol);
                }
            }

            if (!args.isEmpty()) {
                // Send single subscription with all symbols for this data type
                String subscribeMsg = String.format(
                    "{\"op\":\"subscribe\",\"args\":[\"%s\"]}",
                    String.join("\",\"", args)
                );
                client.send(subscribeMsg);
                LOGGER.info("[Bybit-{}] Subscribed to {}: {}", dataType, dataType, symbols);
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
        LOGGER.info("[Bybit-{}] Connected", dataType);
    }

    private void onDisconnected(DataType dataType) {
        connectedStates.put(dataType, false);
        LOGGER.warn("[Bybit-{}] Disconnected, scheduling reconnect...", dataType);
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

                processingTimer.record("BYBIT", "TICKER", result.getParseTimeNanos());
            } else if (actualDataType == DataType.TRADES) {
                Trade trade = result.getAsTrade();

                if (messageHandler != null) {
                    messageHandler.onTrade(trade);
                }

                processingTimer.record("BYBIT", "TRADE", result.getParseTimeNanos());
            } else if (actualDataType == DataType.ORDER_BOOK) {
                OrderBook orderBook = result.getAsOrderBook();

                if (messageHandler != null) {
                    messageHandler.onOrderBook(orderBook);
                }

                processingTimer.record("BYBIT", "ORDER_BOOK", result.getParseTimeNanos());
            }

            totalTimer.stop();
        } catch (Exception e) {
            errorCount.incrementAndGet();
            LOGGER.error("[Bybit-{}] Failed to parse message", sourceDataType, e);
        }
    }

    private void onError(Throwable error, DataType dataType) {
        errorCount.incrementAndGet();
        LOGGER.error("[Bybit-{}] WebSocket error", dataType, error);
    }
}
