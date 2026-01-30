package io.trading.gateway.exchange.bybit;

import io.trading.gateway.core.ProcessingTimer;
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
 * Bybit WebSocket connector.
 * Connects to wss://stream.bybit.com/v5/public/spot
 */
public class BybitConnector implements ExchangeConnector {

    private static final Logger LOGGER = LoggerFactory.getLogger(BybitConnector.class);

    private static final String WS_URL = "wss://stream.bybit.com/v5/public/spot";

    private final BybitMessageParser parser = new BybitMessageParser();
    private final FastCharBybitTickerParser fastCharTickerParser = new FastCharBybitTickerParser();
    private final ProcessingTimer processingTimer = new ProcessingTimer();
    private final AtomicLong messageCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private int debugMsgCount = 0;

    private WebSocketClient client;
    private ReconnectHandler reconnectHandler;
    private ExchangeMessageHandler messageHandler;
    private volatile boolean connected = false;

    @Override
    public Exchange getExchange() {
        return Exchange.BYBIT;
    }

    @Override
    public void connect() {
        if (connected) {
            LOGGER.warn("[Bybit] Already connected");
            return;
        }

        try {
            URI uri = URI.create(WS_URL);

            reconnectHandler = new ReconnectHandler("Bybit", 10, this::doConnect);
            reconnectHandler.start();

            client = new WebSocketClient(
                uri,
                "Bybit",
                this::onMessage,
                this::onError,
                this::onConnected,
                this::onDisconnected,
                false  // Disable compression - Bybit has non-standard permessage-deflate implementation
            );

            doConnect();

        } catch (Exception e) {
            LOGGER.error("[Bybit] Failed to initialize connector", e);
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

        LOGGER.info("[Bybit] Disconnected");
    }

    @Override
    public boolean isConnected() {
        return connected && client != null && client.isConnected();
    }

    @Override
    public void subscribe(Set<String> symbols, Set<DataType> dataTypes) {
        if (!isConnected()) {
            LOGGER.warn("[Bybit] Cannot subscribe, not connected");
            return;
        }

        for (String symbol : symbols) {
            if (dataTypes.contains(DataType.TICKER)) {
                String subscribeMsg = String.format(
                    "{\"op\":\"subscribe\",\"args\":[\"tickers.%s\"]}",
                    symbol
                );
                client.send(subscribeMsg);
                LOGGER.info("[Bybit] Subscribed to ticker for {}", symbol);
            }

            if (dataTypes.contains(DataType.TRADES)) {
                String subscribeMsg = String.format(
                    "{\"op\":\"subscribe\",\"args\":[\"publicTrade.%s\"]}",
                    symbol
                );
                client.send(subscribeMsg);
                LOGGER.info("[Bybit] Subscribed to trades for {}", symbol);
            }

            if (dataTypes.contains(DataType.ORDER_BOOK)) {
                String subscribeMsg = String.format(
                    "{\"op\":\"subscribe\",\"args\":[\"orderbook.1.%s\"]}",
                    symbol
                );
                client.send(subscribeMsg);
                LOGGER.info("[Bybit] Subscribed to order book for {}", symbol);
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

    private void doConnect() {
        if (client != null) {
            client.connect();
        }
    }

    private void onConnected() {
        connected = true;
        reconnectHandler.reset();
        LOGGER.info("[Bybit] Connected");
    }

    private void onDisconnected() {
        connected = false;
        LOGGER.warn("[Bybit] Disconnected, scheduling reconnect...");
        reconnectHandler.scheduleReconnect();
    }

    private void onMessage(String message) {
        messageCount.incrementAndGet();
        if (debugMsgCount < 3) {
            System.err.println("[BYBIT] Message #" + (debugMsgCount + 1) + ": " + message.substring(0, Math.min(500, message.length())));
            debugMsgCount++;
        }

        ProcessingTimer.TimingContext totalTimer = processingTimer.start();

        try {
            // Parse and dispatch message
            if (parser.isTicker(message)) {
                ProcessingTimer.TimingContext parseTimer = processingTimer.start();
                Ticker ticker = fastCharTickerParser.parseTicker(message);
                long parseMicros = parseTimer.stopMicros();

                if (ticker != null && messageHandler != null) {
                    messageHandler.onTicker(ticker);
                }

                processingTimer.record("BYBIT", "TICKER", parseTimer.stop());
                LOGGER.debug("[Bybit] Ticker parsed in {} us", parseMicros);
            } else if (parser.isTrade(message)) {
                ProcessingTimer.TimingContext parseTimer = processingTimer.start();
                Trade trade = parser.parseTrade(message);
                long parseMicros = parseTimer.stopMicros();

                if (trade != null && messageHandler != null) {
                    messageHandler.onTrade(trade);
                }

                processingTimer.record("BYBIT", "TRADE", parseTimer.stop());
                LOGGER.debug("[Bybit] Trade parsed in {} us", parseMicros);
            } else if (parser.isOrderBook(message)) {
                ProcessingTimer.TimingContext parseTimer = processingTimer.start();
                OrderBook orderBook = parser.parseOrderBook(message);
                long parseMicros = parseTimer.stopMicros();

                if (orderBook != null && messageHandler != null) {
                    messageHandler.onOrderBook(orderBook);
                }

                processingTimer.record("BYBIT", "ORDER_BOOK", parseTimer.stop());
                LOGGER.debug("[Bybit] OrderBook parsed in {} us", parseMicros);
            }

            long totalMicros = totalTimer.stopMicros();
            LOGGER.debug("[Bybit] Total message processed in {} us", totalMicros);
        } catch (Exception e) {
            errorCount.incrementAndGet();
            LOGGER.error("[Bybit] Failed to parse message", e);
        }
    }

    private void onError(Throwable error) {
        errorCount.incrementAndGet();
        LOGGER.error("[Bybit] WebSocket error", error);
    }
}
