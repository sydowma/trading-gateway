package io.trading.gateway.exchange.okx;

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
 * OKX WebSocket connector.
 * Connects to wss://ws.okx.com:8443/ws/v5/public
 */
public class OkxConnector implements ExchangeConnector {

    private static final Logger LOGGER = LoggerFactory.getLogger(OkxConnector.class);

    private static final String WS_URL = "wss://ws.okx.com:8443/ws/v5/public";

    private final OkxMessageParser parser = new OkxMessageParser();
    private final FastCharOkxTickerParser fastCharTickerParser = new FastCharOkxTickerParser();
    private final FastCharOkxTradeParser fastCharTradeParser = new FastCharOkxTradeParser();
    private final FastCharOkxOrderBookParser fastCharOrderBookParser = new FastCharOkxOrderBookParser();
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
                this::onDisconnected,
                false  // Disable compression for better compatibility
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
                // Use books5 for lighter weight orderbook (top 5 levels)
                String subscribeMsg = String.format(
                    "{\"op\":\"subscribe\",\"args\":[{\"channel\":\"books5\",\"instId\":\"%s\"}]}",
                    okxSymbol
                );
                client.send(subscribeMsg);
                LOGGER.info("[OKX] Subscribed to order book (books5) for {}", symbol);
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
        LOGGER.info("[OKX] Connected");
    }

    private void onDisconnected() {
        connected = false;
        LOGGER.warn("[OKX] Disconnected, scheduling reconnect...");
        reconnectHandler.scheduleReconnect();
    }

    private void onMessage(String message) {
        messageCount.incrementAndGet();
        if (debugMsgCount < 3 || message.contains("\"trades\"") || message.contains("\"books\"")) {
            System.err.println("[OKX] Message #" + (debugMsgCount + 1) + ": " + message.substring(0, Math.min(500, message.length())));
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

                processingTimer.record("OKX", "TICKER", parseTimer.stop());
                LOGGER.debug("[OKX] Ticker parsed in {} us", parseMicros);
            } else if (parser.isTrade(message)) {
                ProcessingTimer.TimingContext parseTimer = processingTimer.start();
                Trade trade = fastCharTradeParser.parseTrade(message);
                long parseMicros = parseTimer.stopMicros();

                if (trade != null && messageHandler != null) {
                    messageHandler.onTrade(trade);
                }

                processingTimer.record("OKX", "TRADE", parseTimer.stop());
                LOGGER.debug("[OKX] Trade parsed in {} us", parseMicros);
            } else if (parser.isOrderBook(message)) {
                ProcessingTimer.TimingContext parseTimer = processingTimer.start();
                OrderBook orderBook = fastCharOrderBookParser.parseOrderBook(message);
                long parseMicros = parseTimer.stopMicros();

                if (orderBook != null && messageHandler != null) {
                    messageHandler.onOrderBook(orderBook);
                }

                processingTimer.record("OKX", "ORDER_BOOK", parseTimer.stop());
                LOGGER.debug("[OKX] OrderBook parsed in {} us", parseMicros);
            }

            long totalMicros = totalTimer.stopMicros();
            LOGGER.debug("[OKX] Total message processed in {} us", totalMicros);
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
     * OKX uses dash separator for spot pairs (e.g., BTC-USDT, ETH-USDT).
     */
    private String convertSymbolToOkxFormat(String symbol) {
        // Common quote currencies on OKX
        String[] quoteCurrencies = {"USDT", "USDC", "BTC", "ETH", "SOL"};

        for (String quote : quoteCurrencies) {
            if (symbol.endsWith(quote)) {
                String base = symbol.substring(0, symbol.length() - quote.length());
                // Ensure base is not empty
                if (!base.isEmpty()) {
                    return base + "-" + quote;
                }
            }
        }
        // Default: just return as-is if no match
        return symbol;
    }
}
