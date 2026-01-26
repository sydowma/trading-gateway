package io.trading.gateway.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.function.Consumer;

/**
 * Netty handler for WebSocket client connections.
 * Handles handshake, frame processing, and connection lifecycle events.
 */
public class WebSocketClientHandler extends SimpleChannelInboundHandler<Object> {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebSocketClientHandler.class);

    private final WebSocketClientHandshaker handshaker;
    private final Consumer<String> messageHandler;
    private final Consumer<Throwable> errorHandler;
    private final Runnable connectHandler;
    private final Runnable disconnectHandler;

    public WebSocketClientHandler(
        URI uri,
        Consumer<String> messageHandler,
        Consumer<Throwable> errorHandler,
        Runnable connectHandler,
        Runnable disconnectHandler
    ) {
        this.handshaker = WebSocketClientHandshakerFactory.newHandshaker(
            uri,
            WebSocketVersion.V13,
            null,
            true,
            new io.netty.handler.codec.http.DefaultHttpHeaders()
        );
        this.messageHandler = messageHandler;
        this.errorHandler = errorHandler;
        this.connectHandler = connectHandler;
        this.disconnectHandler = disconnectHandler;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        handshaker.handshake(ctx.channel());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        LOGGER.debug("WebSocket channel inactive");
        if (disconnectHandler != null) {
            disconnectHandler.run();
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!handshaker.isHandshakeComplete()) {
            try {
                handshaker.finishHandshake(ctx.channel(), (FullHttpResponse) msg);
                LOGGER.debug("WebSocket handshake complete");
                if (connectHandler != null) {
                    connectHandler.run();
                }
            } catch (Exception e) {
                LOGGER.error("WebSocket handshake failed", e);
                if (errorHandler != null) {
                    errorHandler.accept(e);
                }
                ctx.close();
            }
            return;
        }

        if (msg instanceof FullHttpResponse response) {
            throw new IllegalStateException(
                "Unexpected FullHttpResponse (status=" + response.status() + ")"
            );
        }

        WebSocketFrame frame = (WebSocketFrame) msg;

        if (frame instanceof PingWebSocketFrame ping) {
            // Respond to ping with pong
            ctx.write(new PongWebSocketFrame(ping.content().retain()));
            return;
        }

        if (frame instanceof TextWebSocketFrame textFrame) {
            String message = textFrame.text();
            try {
                if (messageHandler != null) {
                    messageHandler.accept(message);
                }
            } catch (Exception e) {
                LOGGER.error("Error in message handler", e);
                if (errorHandler != null) {
                    errorHandler.accept(e);
                }
            }
            return;
        }

        if (frame instanceof CloseWebSocketFrame) {
            LOGGER.debug("Received close frame");
            ctx.close();
            return;
        }

        LOGGER.warn("Unsupported frame type: {}", frame.getClass().getName());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.error("WebSocket exception", cause);
        if (errorHandler != null) {
            errorHandler.accept(cause);
        }
        ctx.close();
    }
}
