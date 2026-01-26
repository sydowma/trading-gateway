package io.trading.gateway.exchange.binance;

import io.trading.gateway.exchange.ExchangeConnector;
import io.trading.gateway.exchange.ExchangeMessageHandler;
import io.trading.gateway.model.DataType;
import io.trading.gateway.model.Exchange;
import io.trading.gateway.model.OrderBook;
import io.trading.gateway.model.Ticker;
import io.trading.gateway.model.Trade;
import io.trading.gateway.netty.ReconnectHandler;
import io.trading.gateway.netty.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Binance WebSocket connector.
 * Connects to wss://stream.binance.com:9443/ws
 */
public class BinanceConnector implements ExchangeConnector {

    private static final Logger LOGGER = LoggerFactory.getLogger(BinanceConnector.class);

    private static final String WS_URL = "wss://stream.binance.com:9443/ws";

    private final BinanceMessageParser parser = new BinanceMessageParser();
    private final AtomicLong messageCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    private WebSocketClient client;
    private ReconnectHandler reconnectHandler;
    private ExchangeMessageHandler messageHandler;
    private volatile boolean connected = false;

    @Override
    public Exchange getExchange() {
        return Exchange.BINANCE;
    }

    @Override
    public void connect() {
        if (connected) {
            LOGGER.warn("[Binance] Already connected");
            return;
        }

        try {
            URI uri = URI.create(WS_URL);

            reconnectHandler = new ReconnectHandler("Binance", 10, this::doConnect);
            reconnectHandler.start();

            client = new WebSocketClient(
                uri,
                "Binance",
                this::onMessage,
                this::onError,
                this::onConnected,
                this::onDisconnected
            );

            doConnect();

        } catch (Exception e) {
            LOGGER.error("[Binance] Failed to initialize connector", e);
        }
    }

    @Override
    public void disconnect() {
        connected = false;

        if (reconnectHandler != null) {
            reconnectHandler.stop();
            reconnectHandler = null;
        }

        if (client != null) {
            client.close();
            client = null;
        }

        LOGGER.info("[Binance] Disconnected");
    }

    @Override
    public boolean isConnected() {
        return connected && client != null && client.isConnected();
    }

    @Override
    public void subscribe(Set<String> symbols, Set<DataType> dataTypes) {
        if (!isConnected()) {
            LOGGER.warn("[Binance] Cannot subscribe, not connected");
            return;
        }

        for (String symbol : symbols) {
            String lowerSymbol = symbol.toLowerCase();

            if (dataTypes.contains(DataType.TICKER)) {
                String subscribeMsg = String.format(
                    "{\"method\":\"SUBSCRIBE\",\"params\":[\"%s@ticker\"],\"id\":%d}",
                    lowerSymbol,
                    System.currentTimeMillis()
                );
                client.send(subscribeMsg);
                LOGGER.info("[Binance] Subscribed to ticker for {}", symbol);
            }

            if (dataTypes.contains(DataType.TRADES)) {
                String subscribeMsg = String.format(
                    "{\"method\":\"SUBSCRIBE\",\"params\":[\"%s@trade\"],\"id\":%d}",
                    lowerSymbol,
                    System.currentTimeMillis()
                );
                client.send(subscribeMsg);
                LOGGER.info("[Binance] Subscribed to trades for {}", symbol);
            }

            if (dataTypes.contains(DataType.ORDER_BOOK)) {
                String subscribeMsg = String.format(
                    "{\"method\":\"SUBSCRIBE\",\"params\":[\"%s@depth\"],\"id\":%d}",
                    lowerSymbol,
                    System.currentTimeMillis()
                );
                client.send(subscribeMsg);
                LOGGER.info("[Binance] Subscribed to order book for {}", symbol);
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
    public void close() {
        disconnect();
    }

    private void doConnect() {
        if (client != null) {
            client.connect();
        }
    }

    private void onConnected() {
        connected = true;
        reconnectHandler.reset();
        LOGGER.info("[Binance] Connected");
    }

    private void onDisconnected() {
        connected = false;
        LOGGER.warn("[Binance] Disconnected, scheduling reconnect...");
        reconnectHandler.scheduleReconnect();
    }

    private void onMessage(String message) {
        messageCount.incrementAndGet();

        try {
            // Parse and dispatch message
            if (parser.isTicker(message)) {
                Ticker ticker = parser.parseTicker(message);
                if (ticker != null && messageHandler != null) {
                    messageHandler.onTicker(ticker);
                }
            } else if (parser.isTrade(message)) {
                Trade trade = parser.parseTrade(message);
                if (trade != null && messageHandler != null) {
                    messageHandler.onTrade(trade);
                }
            } else if (parser.isOrderBook(message)) {
                OrderBook orderBook = parser.parseOrderBook(message);
                if (orderBook != null && messageHandler != null) {
                    messageHandler.onOrderBook(orderBook);
                }
            }
        } catch (Exception e) {
            errorCount.incrementAndGet();
            LOGGER.error("[Binance] Failed to parse message", e);
        }
    }

    private void onError(Throwable error) {
        errorCount.incrementAndGet();
        LOGGER.error("[Binance] WebSocket error", error);
    }
}
