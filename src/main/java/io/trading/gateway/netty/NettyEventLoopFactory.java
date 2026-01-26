package io.trading.gateway.netty;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

/**
 * Factory for creating Netty event loop groups.
 * Uses epoll on Linux for optimal performance, NIO on other platforms.
 */
public class NettyEventLoopFactory {

    private static final boolean EPOLL_AVAILABLE = Epoll.isAvailable();

    static {
        if (EPOLL_AVAILABLE) {
            System.out.println("Netty: Using native epoll transport");
        } else {
            System.out.println("Netty: Using NIO transport");
        }
    }

    /**
     * Creates an EventLoopGroup with the specified number of threads.
     *
     * @param threads Number of threads
     * @return EventLoopGroup instance
     */
    public static EventLoopGroup createEventLoopGroup(int threads) {
        if (EPOLL_AVAILABLE) {
            return new EpollEventLoopGroup(threads);
        } else {
            return new NioEventLoopGroup(threads);
        }
    }

    /**
     * Gets the appropriate SocketChannel class for the current platform.
     *
     * @return SocketChannel class
     */
    public static Class<? extends SocketChannel> getClientChannelClass() {
        if (EPOLL_AVAILABLE) {
            return EpollSocketChannel.class;
        } else {
            return NioSocketChannel.class;
        }
    }

    /**
     * Returns whether epoll transport is available.
     */
    public static boolean isEpollAvailable() {
        return EPOLL_AVAILABLE;
    }
}
