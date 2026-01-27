package io.trading.gateway.example;

import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import io.trading.gateway.aeron.BinaryDecoder;
import io.trading.gateway.aeron.StreamRegistry;
import io.trading.gateway.model.DataType;
import io.trading.gateway.model.Exchange;
import io.trading.gateway.model.OrderBook;
import io.trading.gateway.model.Ticker;
import io.trading.gateway.model.Trade;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.SigInt;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Example Aeron consumer that subscribes to market data from the Trading Gateway.
 *
 * This demonstrates how to:
 * 1. Connect to Aeron IPC
 * 2. Subscribe to specific exchange/data type streams
 * 3. Decode binary market data messages
 *
 * Usage:
 * <pre>
 * java --enable-preview -cp trading-gateway-1.0.0.jar \
 *   io.trading.gateway.example.MarketDataConsumer
 * </pre>
 */
public class MarketDataConsumer implements AutoCloseable {

    private final Aeron aeron;
    private final Subscription tickerSubscription;
    private final Subscription tradeSubscription;
    private final Subscription orderBookSubscription;

    private final AtomicLong tickerCount = new AtomicLong(0);
    private final AtomicLong tradeCount = new AtomicLong(0);
    private final AtomicLong orderBookCount = new AtomicLong(0);

    /**
     * Creates a new market data consumer.
     *
     * @param aeronDir The Aeron directory (must match gateway's AERON_DIR)
     * @param exchange The exchange to subscribe to
     */
    public MarketDataConsumer(String aeronDir, Exchange exchange) {
        // Configure Aeron context
        Aeron.Context context = new Aeron.Context()
            .aeronDirectoryName(aeronDir);

        this.aeron = Aeron.connect(context);

        // Create subscriptions for each data type
        String tickerChannel = StreamRegistry.getChannel(exchange, DataType.TICKER);
        int tickerStreamId = StreamRegistry.getStreamId(exchange, DataType.TICKER);

        String tradeChannel = StreamRegistry.getChannel(exchange, DataType.TRADES);
        int tradeStreamId = StreamRegistry.getStreamId(exchange, DataType.TRADES);

        String orderBookChannel = StreamRegistry.getChannel(exchange, DataType.ORDER_BOOK);
        int orderBookStreamId = StreamRegistry.getStreamId(exchange, DataType.ORDER_BOOK);

        this.tickerSubscription = aeron.addSubscription(tickerChannel, tickerStreamId);
        this.tradeSubscription = aeron.addSubscription(tradeChannel, tradeStreamId);
        this.orderBookSubscription = aeron.addSubscription(orderBookChannel, orderBookStreamId);

        System.out.println("Market Data Consumer started");
        System.out.println("Exchange: " + exchange);
        System.out.println("Ticker: " + tickerChannel + " (stream " + tickerStreamId + ")");
        System.out.println("Trade: " + tradeChannel + " (stream " + tradeStreamId + ")");
        System.out.println("OrderBook: " + orderBookChannel + " (stream " + orderBookStreamId + ")");
    }

    /**
     * Starts consuming messages. Runs until interrupted.
     */
    public void consume() throws InterruptedException {
        AtomicBoolean running = new AtomicBoolean(true);

        // Setup signal handler for graceful shutdown
        SigInt.register(() -> {
            System.out.println("\nShutdown signal received");
            running.set(false);
        });

        System.out.println("\nWaiting for messages... (Press Ctrl+C to stop)\n");

        while (running.get()) {
            int messagesProcessed = 0;

            // Poll for ticker messages
            messagesProcessed += pollTicker();

            // Poll for trade messages
            messagesProcessed += pollTrade();

            // Poll for order book messages
            messagesProcessed += pollOrderBook();

            if (messagesProcessed == 0) {
                // No messages, sleep briefly
                Thread.sleep(1);
            }
        }

        System.out.println("\n=== Summary ===");
        System.out.println("Tickers received: " + tickerCount.get());
        System.out.println("Trades received: " + tradeCount.get());
        System.out.println("Order books received: " + orderBookCount.get());
        System.out.println("Total: " + (tickerCount.get() + tradeCount.get() + orderBookCount.get()));
    }

    private int pollTicker() {
        return tickerSubscription.poll(new FragmentHandler() {
            @Override
            public void onFragment(DirectBuffer buffer, int offset, int length, io.aeron.logbuffer.Header header) {
                try {
                    Ticker ticker = BinaryDecoder.decodeTicker(buffer, offset);
                    tickerCount.incrementAndGet();

                    // Print ticker info
                    System.out.printf("[TICKER] %s %s: Last=%.2f Bid=%.2f Ask=%.2f Vol=%.2f%n",
                        ticker.exchange(),
                        ticker.symbol(),
                        ticker.lastPrice(),
                        ticker.bidPrice(),
                        ticker.askPrice(),
                        ticker.volume24h()
                    );
                } catch (Exception e) {
                    System.err.println("Failed to decode ticker: " + e.getMessage());
                }
            }
        }, 10);
    }

    private int pollTrade() {
        return tradeSubscription.poll(new FragmentHandler() {
            @Override
            public void onFragment(DirectBuffer buffer, int offset, int length, io.aeron.logbuffer.Header header) {
                try {
                    Trade trade = BinaryDecoder.decodeTrade(buffer, offset);
                    tradeCount.incrementAndGet();

                    // Print trade info
                    System.out.printf("[TRADE] %s %s: Price=%.2f Qty=%.4f Side=%s ID=%s%n",
                        trade.exchange(),
                        trade.symbol(),
                        trade.price(),
                        trade.quantity(),
                        trade.side(),
                        trade.tradeId()
                    );
                } catch (Exception e) {
                    System.err.println("Failed to decode trade: " + e.getMessage());
                }
            }
        }, 10);
    }

    private int pollOrderBook() {
        return orderBookSubscription.poll(new FragmentHandler() {
            @Override
            public void onFragment(DirectBuffer buffer, int offset, int length, io.aeron.logbuffer.Header header) {
                try {
                    OrderBook orderBook = BinaryDecoder.decodeOrderBook(buffer, offset);
                    orderBookCount.incrementAndGet();

                    // Print order book summary
                    if (!orderBook.bids().isEmpty() && !orderBook.asks().isEmpty()) {
                        System.out.printf("[ORDERBOOK] %s %s: Best Bid=%.2f (%.4f) Best Ask=%.2f (%.4f) Levels=%d/%d%n",
                            orderBook.exchange(),
                            orderBook.symbol(),
                            orderBook.bids().get(0).price(),
                            orderBook.bids().get(0).quantity(),
                            orderBook.asks().get(0).price(),
                            orderBook.asks().get(0).quantity(),
                            orderBook.bids().size(),
                            orderBook.asks().size()
                        );
                    }
                } catch (Exception e) {
                    System.err.println("Failed to decode order book: " + e.getMessage());
                }
            }
        }, 10);
    }

    @Override
    public void close() {
        CloseHelper.closeAll(tickerSubscription, tradeSubscription, orderBookSubscription, aeron);
    }

    /**
     * Main entry point.
     */
    public static void main(String[] args) {
        // Default configuration
        String aeronDir = System.getenv("AERON_DIR");
        if (aeronDir == null || aeronDir.isEmpty()) {
            aeronDir = "/dev/shm/trading-gateway-gateway-0";
        }

        Exchange exchange = args.length > 0
            ? Exchange.valueOf(args[0].toUpperCase())
            : Exchange.BINANCE;

        System.out.println("========================================");
        System.out.println("   Trading Gateway - Market Data Consumer");
        System.out.println("========================================");
        System.out.println("Aeron Dir: " + aeronDir);
        System.out.println("Exchange: " + exchange);
        System.out.println("========================================");

        try (MarketDataConsumer consumer = new MarketDataConsumer(aeronDir, exchange)) {
            consumer.consume();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Consumer interrupted");
        }
    }
}
