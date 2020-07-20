package com.moesrc.socketio;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.JdkLoggerFactory;

import javax.net.ssl.SSLException;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class Manager {

    static {
        // no any slf4j and log4j
        InternalLoggerFactory.setDefaultFactory(JdkLoggerFactory.INSTANCE);
    }

    private static volatile Manager manager;

    private static final ThreadFactory THREAD_FACTORY = new ThreadFactory() {
        private final AtomicInteger nextId = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable task) {
            String name = "socket.io-event-worker-" + nextId.getAndIncrement();
            Thread thread = new Thread(null, task, name, 0);
            return thread;
        }
    };

    public static Manager getInstance() {
        return getInstance(new ManagerOption());
    }

    public static Manager getInstance(ManagerOption option) {
        Manager mng = manager;
        if (mng == null) {
            synchronized (Manager.class) {
                mng = manager;
                if (mng == null) {
                    manager = mng = new Manager(option);
                }
            }
        }
        return mng;
    }

    public static void destroy() {
        Manager mng = manager;
        if (mng != null) {
            synchronized (Manager.class) {
                mng = manager;
                if (mng != null) {
                    mng.destroy0();
                    manager = null;
                }
            }
        }
    }

    private EventLoopGroup bossGroup;
    private Bootstrap b;

    private PacketEncoder encoder = new PacketEncoder();
    private PacketDecoder decoder = new PacketDecoder();
    private HashedWheelScheduler scheduler = new HashedWheelScheduler();
    private ExecutorService executorService;
    private Map<String, SocketIOClient> sockets = new ConcurrentHashMap<>();
    private SocketIOEncoderHandler socketIoEncoderHandler = new SocketIOEncoderHandler(encoder);
    private WebSocketClientCompressionHandler compressionHandler = WebSocketClientCompressionHandler.INSTANCE;


    private Manager(ManagerOption option) {

        bossGroup = new NioEventLoopGroup(0);
        b = new Bootstrap();

        b.group(bossGroup);
        b.channel(NioSocketChannel.class);
        b.option(ChannelOption.SO_KEEPALIVE, option.isKeepAlive());
        b.option(ChannelOption.TCP_NODELAY, option.isTcpNoDelay());
        b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, option.getConnectTimeout());

        if (option.executorService != null) {
            this.executorService = option.executorService;
        } else {
            this.executorService = Executors.newSingleThreadExecutor(THREAD_FACTORY);
        }
    }


    private void destroy0() {
        // close all socket
        Iterator<Map.Entry<String, SocketIOClient>> socketIterator = sockets.entrySet().iterator();
        while (socketIterator.hasNext()) {
            Map.Entry<String, SocketIOClient> next = socketIterator.next();
            next.getValue().disconnect();
            socketIterator.remove();
        }

        scheduler.shutdown();
        bossGroup.shutdownGracefully().syncUninterruptibly();

        if (executorService != null) {
            executorService.shutdown();
        }

    }

    public SocketIOClient create(SocketOption option) throws SSLException, URISyntaxException {
        return create("", option);
    }

    public SocketIOClient create(String tag, SocketOption option) throws SSLException, URISyntaxException {
        SocketIOClient socket = sockets.get(tag);
        if (socket != null) {
            socket.disconnect();
        }
        socket = new SocketIOClient()
                .bootstrap(b)
                .encoderHandler(socketIoEncoderHandler)
                .compressionHandler(compressionHandler)
                .decoder(decoder)
                .scheduler(scheduler)
                .executorService(executorService)
                .option(option);
        sockets.put(tag, socket);
        return socket.init();
    }

    public static class ManagerOption {
        private Integer connectTimeout = 5000;
        private Boolean keepAlive = false;
        private Boolean tcpNoDelay = true;
        private ExecutorService executorService = null;

        public int getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(int connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public boolean isKeepAlive() {
            return keepAlive;
        }

        public void setKeepAlive(boolean keepAlive) {
            this.keepAlive = keepAlive;
        }

        public boolean isTcpNoDelay() {
            return tcpNoDelay;
        }

        public void setTcpNoDelay(boolean tcpNoDelay) {
            this.tcpNoDelay = tcpNoDelay;
        }

        public ExecutorService getExecutorService() {
            return executorService;
        }

        public void setExecutorService(ExecutorService executorService) {
            this.executorService = executorService;
        }
    }
}
