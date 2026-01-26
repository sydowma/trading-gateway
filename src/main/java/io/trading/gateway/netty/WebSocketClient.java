package io.trading.gateway.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.function.Consumer;

/**
 * Netty-based WebSocket client for connecting to exchange streaming APIs.
 * Supports both epoll (Linux) and NIO (universal) event loop groups.
 */
public class WebSocketClient implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebSocketClient.class);

    private final URI uri;
    private final String name;
    private final Consumer<String> messageHandler;
    private final Consumer<Throwable> errorHandler;
    private final Runnable connectHandler;
    private final Runnable disconnectHandler;

    private EventLoopGroup eventLoopGroup;
    private Channel channel;
    private volatile boolean connected = false;

    /**
     * Creates a new WebSocket client.
     *
     * @param uri              The WebSocket URI to connect to
     * @param name             Friendly name for this client (e.g., "Binance")
     * @param messageHandler   Callback for received text messages
     * @param errorHandler     Callback for errors
     * @param connectHandler   Callback when connection is established
     * @param disconnectHandler Callback when connection is lost
     */
    public WebSocketClient(
        URI uri,
        String name,
        Consumer<String> messageHandler,
        Consumer<Throwable> errorHandler,
        Runnable connectHandler,
        Runnable disconnectHandler
    ) {
        this.uri = uri;
        this.name = name;
        this.messageHandler = messageHandler;
        this.errorHandler = errorHandler;
        this.connectHandler = connectHandler;
        this.disconnectHandler = disconnectHandler;
    }

    /**
     * Connects to the WebSocket server.
     */
    public void connect() {
        if (connected) {
            LOGGER.warn("{}: Already connected", name);
            return;
        }

        try {
            // Create event loop group (epoll for Linux, NIO for others)
            eventLoopGroup = NettyEventLoopFactory.createEventLoopGroup(1);

            var bootstrap = new io.netty.bootstrap.Bootstrap();
            bootstrap.group(eventLoopGroup)
                .channel(NettyEventLoopFactory.getClientChannelClass())
                .handler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ChannelPipeline pipeline = ch.pipeline();

                        // HTTP codec
                        pipeline.addLast(new HttpClientCodec());

                        // HTTP object aggregator for handshake
                        pipeline.addLast(new HttpObjectAggregator(8192));

                        // WebSocket compression
                        pipeline.addLast(WebSocketClientCompressionHandler.INSTANCE);

                        // WebSocket handshake and frame handler
                        pipeline.addLast(new WebSocketClientHandler(
                            uri,
                            messageHandler,
                            errorHandler,
                            () -> {
                                connected = true;
                                LOGGER.info("{}: Connected", name);
                                if (connectHandler != null) {
                                    connectHandler.run();
                                }
                            },
                            () -> {
                                connected = false;
                                LOGGER.warn("{}: Disconnected", name);
                                if (disconnectHandler != null) {
                                    disconnectHandler.run();
                                }
                            }
                        ));
                    }
                });

            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : (uri.getScheme().equals("wss") ? 443 : 80);

            LOGGER.info("{}: Connecting to {}:{}...", name, host, port);
            channel = bootstrap.connect(host, port).sync().channel();

        } catch (Exception e) {
            LOGGER.error("{}: Failed to connect", name, e);
            if (errorHandler != null) {
                errorHandler.accept(e);
            }
            close();
        }
    }

    /**
     * Sends a text message through the WebSocket.
     *
     * @param message The message to send
     */
    public void send(String message) {
        if (!connected || channel == null) {
            LOGGER.warn("{}: Cannot send message, not connected", name);
            return;
        }

        try {
            io.netty.handler.codec.http.websocketx.TextWebSocketFrame frame =
                new io.netty.handler.codec.http.websocketx.TextWebSocketFrame(message);
            channel.writeAndFlush(frame);
        } catch (Exception e) {
            LOGGER.error("{}: Failed to send message", name, e);
            if (errorHandler != null) {
                errorHandler.accept(e);
            }
        }
    }

    /**
     * Returns whether the client is currently connected.
     */
    public boolean isConnected() {
        return connected && channel != null && channel.isActive();
    }

    @Override
    public void close() {
        connected = false;

        if (channel != null) {
            try {
                channel.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.error("{}: Interrupted while closing channel", name, e);
            }
            channel = null;
        }

        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
            eventLoopGroup = null;
        }

        LOGGER.info("{}: Closed", name);
    }
}
