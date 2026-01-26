package io.trading.gateway.exchange.okx;

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

/**
 * OKX WebSocket connector.
 * Connects to wss://ws.okx.com:8443/ws/v5/public
 */
public class OkxConnector implements ExchangeConnector {

    private static final Logger LOGGER = LoggerFactory.getLogger(OkxConnector.class);

    private static final String WS_URL = "wss://ws.okx.com:8443/ws/v5/public";

    private final OkxMessageParser parser = new OkxMessageParser();
    private final AtomicLong messageCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    private WebSocketClient client;
    private ReconnectHandler reconnectHandler;
    private ExchangeMessageHandler messageHandler;
    private volatile boolean connected = false;

    @Override
    public Exchange getExchange() {
        return Exchange.OKX;
    }

    @Override
    public void connect() {
        if (connected) {
            LOGGER.warn("[OKX] Already connected");
            return;
        }

        try {
            URI uri = URI.create(WS_URL);

            reconnectHandler = new ReconnectHandler("OKX", 10, this::doConnect);
            reconnectHandler.start();

            client = new WebSocketClient(
                uri,
                "OKX",
                this::onMessage,
                this::onError,
                this::onConnected,
                this::onDisconnected
            );

            doConnect();

        } catch (Exception e) {
            LOGGER.error("[OKX] Failed to initialize connector", e);
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

        LOGGER.info("[OKX] Disconnected");
    }

    @Override
    public boolean isConnected() {
        return connected && client != null && client.isConnected();
    }

    @Override
    public void subscribe(Set<String> symbols, Set<DataType> dataTypes) {
        if (!isConnected()) {
            LOGGER.warn("[OKX] Cannot subscribe, not connected");
            return;
        }

        for (String symbol : symbols) {
            String okxSymbol = convertSymbolToOkxFormat(symbol);

            if (dataTypes.contains(DataType.TICKER)) {
                String subscribeMsg = String.format(
                    "{\"op\":\"subscribe\",\"args\":[{\"channel\":\"tickers\",\"instId\":\"%s\"}]}",
                    okxSymbol
                );
                client.send(subscribeMsg);
                LOGGER.info("[OKX] Subscribed to ticker for {}", symbol);
            }

            if (dataTypes.contains(DataType.TRADES)) {
                String subscribeMsg = String.format(
                    "{\"op\":\"subscribe\",\"args\":[{\"channel\":\"trades\",\"instId\":\"%s\"}]}",
                    okxSymbol
                );
                client.send(subscribeMsg);
                LOGGER.info("[OKX] Subscribed to trades for {}", symbol);
            }

            if (dataTypes.contains(DataType.ORDER_BOOK)) {
                String subscribeMsg = String.format(
                    "{\"op\":\"subscribe\",\"args\":[{\"channel\":\"books\",\"instId\":\"%s\"}]}",
                    okxSymbol
                );
                client.send(subscribeMsg);
                LOGGER.info("[OKX] Subscribed to order book for {}", symbol);
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
        LOGGER.info("[OKX] Connected");
    }

    private void onDisconnected() {
        connected = false;
        LOGGER.warn("[OKX] Disconnected, scheduling reconnect...");
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
            LOGGER.error("[OKX] Failed to parse message", e);
        }
    }

    private void onError(Throwable error) {
        errorCount.incrementAndGet();
        LOGGER.error("[OKX] WebSocket error", error);
    }

    /**
     * Converts symbol format (e.g., BTCUSDT -> BTC-USDT for OKX).
     */
    private String convertSymbolToOkxFormat(String symbol) {
        // OKX uses dash separator for spot pairs
        // Simple heuristic: insert dash before quote currency
        if (symbol.endsWith("USDT")) {
            String base = symbol.substring(0, symbol.length() - 5);
            return base + "-USDT";
        }
        // Default: just return as-is
        return symbol;
    }
}
