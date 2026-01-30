package io.trading.gateway.exchange;

import io.trading.gateway.core.ProcessingTimer;
import io.trading.gateway.model.Exchange;
import io.trading.gateway.model.DataType;

import java.util.Set;

/**
 * Interface for exchange WebSocket connectors.
 * Implementations handle connection management, subscription, and message parsing.
 */
public interface ExchangeConnector extends AutoCloseable {

    /**
     * Gets the exchange identifier.
     */
    Exchange getExchange();

    /**
     * Connects to the exchange WebSocket API.
     */
    void connect();

    /**
     * Disconnects from the exchange WebSocket API.
     */
    void disconnect();

    /**
     * Returns whether the connector is currently connected.
     */
    boolean isConnected();

    /**
     * Subscribes to market data for the given symbols.
     *
     * @param symbols   Symbols to subscribe
     * @param dataTypes Data types to subscribe
     */
    void subscribe(Set<String> symbols, Set<DataType> dataTypes);

    /**
     * Sets the message handler for parsed market data.
     *
     * @param handler The handler to call when market data is received
     */
    void setMessageHandler(ExchangeMessageHandler handler);

    /**
     * Gets the number of messages received.
     */
    long getMessageCount();

    /**
     * Gets the number of parsing errors.
     */
    long getErrorCount();

    /**
     * Gets the processing timer for this connector.
     * Used to track message parsing latency.
     */
    ProcessingTimer getProcessingTimer();
}
