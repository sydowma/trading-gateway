package io.trading.gateway.exchange.binance;

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

    // Use fast parser for better performance
    private final FastBinanceParser parser = new FastBinanceParser();
    private final ProcessingTimer processingTimer = new ProcessingTimer();
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
                this::onDisconnected,
                true  // Enable compression for Binance
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

        // Use combined streams format: {"method":"SUBSCRIBE","params":["btcusdt@ticker","btcusdt@trade"],"id":1}
        StringBuilder streamsBuilder = new StringBuilder();
        for (String symbol : symbols) {
            String lowerSymbol = symbol.toLowerCase();

            if (dataTypes.contains(DataType.TICKER)) {
                if (streamsBuilder.length() > 0) streamsBuilder.append("/");
                streamsBuilder.append(lowerSymbol).append("@ticker");
            }

            if (dataTypes.contains(DataType.TRADES)) {
                if (streamsBuilder.length() > 0) streamsBuilder.append("/");
                streamsBuilder.append(lowerSymbol).append("@trade");
            }

            if (dataTypes.contains(DataType.ORDER_BOOK)) {
                if (streamsBuilder.length() > 0) streamsBuilder.append("/");
                streamsBuilder.append(lowerSymbol).append("@depth");
            }
        }

        // Send combined subscription request
        String subscribeMsg = String.format(
            "{\"method\":\"SUBSCRIBE\",\"params\":[\"%s\"],\"id\":%d}",
            streamsBuilder.toString().replace("/", "\",\""),
            System.currentTimeMillis()
        );
        client.send(subscribeMsg);
        LOGGER.info("[Binance] Subscribed to streams: {}", streamsBuilder);
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
        LOGGER.info("[Binance] Connected");
    }

    private void onDisconnected() {
        connected = false;
        LOGGER.warn("[Binance] Disconnected, scheduling reconnect...");
        reconnectHandler.scheduleReconnect();
    }

    private void onMessage(String message) {
        messageCount.incrementAndGet();

        ProcessingTimer.TimingContext totalTimer = processingTimer.start();

        try {
            // Parse and dispatch message using fast parser
            DataType dataType = parser.parseMessageType(message);

            if (dataType == DataType.TICKER) {
                ProcessingTimer.TimingContext parseTimer = processingTimer.start();
                Ticker ticker = parser.parseTicker(message);
                long parseMicros = parseTimer.stopMicros();

                if (ticker != null && messageHandler != null) {
                    messageHandler.onTicker(ticker);
                }

                processingTimer.record("BINANCE", "TICKER", parseTimer.stop());
                LOGGER.debug("[Binance] Ticker parsed in {} us", parseMicros);
            } else if (dataType == DataType.TRADES) {
                ProcessingTimer.TimingContext parseTimer = processingTimer.start();
                Trade trade = parser.parseTrade(message);
                long parseMicros = parseTimer.stopMicros();

                if (trade != null && messageHandler != null) {
                    messageHandler.onTrade(trade);
                }

                processingTimer.record("BINANCE", "TRADE", parseTimer.stop());
                LOGGER.debug("[Binance] Trade parsed in {} us", parseMicros);
            } else if (dataType == DataType.ORDER_BOOK) {
                ProcessingTimer.TimingContext parseTimer = processingTimer.start();
                OrderBook orderBook = parser.parseOrderBook(message);
                long parseMicros = parseTimer.stopMicros();

                if (orderBook != null && messageHandler != null) {
                    messageHandler.onOrderBook(orderBook);
                }

                processingTimer.record("BINANCE", "ORDER_BOOK", parseTimer.stop());
                LOGGER.debug("[Binance] OrderBook parsed in {} us", parseMicros);
            }

            long totalMicros = totalTimer.stopMicros();
            LOGGER.debug("[Binance] Total message processed in {} us", totalMicros);
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
