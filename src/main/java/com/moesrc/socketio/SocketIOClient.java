package com.moesrc.socketio;

import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker13;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import javax.net.ssl.SSLException;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.QueryStringEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

public class SocketIOClient extends Emitter {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(SocketIOClient.class);

    enum SOCKET_STATE {
        DISCONNECTED, DISCONNECTING, NEW, READY_TO_CONNECT, CONNECTING, RE_CONNECTING, PING_TIMEOUT, CONNECTED
    }

    enum SCHEDULE_KEY {
        RECONNECT, PING, PING_TIMEOUT, ACK_TIMEOUT
    }

    private Bootstrap bootstrap;
    private PacketDecoder decoder;
    private PacketEncoder encoder;
    private Channel channel;
    private ChannelHandlerContext ctx;

    // run state
    private AtomicReference<SOCKET_STATE> state = new AtomicReference<>(SOCKET_STATE.NEW);
    private AtomicInteger tryReconnect = new AtomicInteger(0);

    private URI uri;
    private String scheme;
    private String host;
    private int port;
    private boolean ssl = false;
    private SslContext sslCtx;
    private String sid;
    private int reconnectMax = 0;
    private boolean reconnection = true;
    private long reconnectionDelay = 5000;
    private long pingTimeout = 60000;
    private long pingInterval = 25000;
    private long lastPingTimestamp = 0L;

    private Packet lastBinaryPacket;

    private Map<String, String> headers = new HashMap<>();
    private AckEntry<Object[]> ackEntry = new AckEntry();
    private Map<String, Object> params = new HashMap<>();

    private HashedWheelScheduler scheduler;
    private ExecutorService executorService;
    private Set<String> keyList = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    public SocketIOClient connect() {
        switch (state.get()) {
            case DISCONNECTED:
                state.set(SOCKET_STATE.READY_TO_CONNECT);
            case NEW:
            case READY_TO_CONNECT:
                state.set(SOCKET_STATE.CONNECTING);

                tryReconnect.set(1);
                connect0();
        }
        return this;
    }

    public void disconnect() {
        logger.info("will close connection");

        state.set(SOCKET_STATE.DISCONNECTING);
        disconnect0();
    }

    public boolean isConnected() {
        return SOCKET_STATE.CONNECTED == state.get();
    }

    public Emitter emit(final Packet packet) {
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                if (!isConnected()) {
                    SocketIOClient.super.emit(EventType.EVENT_ERROR, new SocketNotAvailableException("socket is not ready. current state is [" + state.get() + "]"));
                    return;
                }

                ctx.channel().writeAndFlush(packet);
            }
        });
        return this;
    }

    @Override
    public Emitter emit(final String name, final Object... args) {
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                if (!isConnected()) {
                    SocketIOClient.super.emit(EventType.EVENT_ERROR, new SocketNotAvailableException("socket is not ready. current state is [" + state.get() + "]"));
                    return;
                }

                Packet packet = new Packet(PacketType.MESSAGE);
                packet.setSubType(PacketType.EVENT);
                packet.setName(name);
                packet.setData(Arrays.asList(args));
                ctx.channel().writeAndFlush(packet);
            }
        });
        return this;
    }

    public Emitter emit(final String name, final AckCallback<Object[]> callback, final Object... data) {
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                if (!isConnected()) {
                    callback.onTimeout();
                    SocketIOClient.super.emit(EventType.EVENT_ERROR, new SocketNotAvailableException("socket is not ready. current state is [" + state.get() + "]"));
                    return;
                }

                long ackId = ackEntry.addAckCallback(callback);
                scheduleAckTimeout(ackId, callback);

                Packet packet = new Packet(PacketType.MESSAGE);
                packet.setSubType(PacketType.EVENT);
                packet.setName(name);
                packet.setData(Arrays.asList(data));
                packet.setAckId(ackId);
                ctx.channel().writeAndFlush(packet);
            }
        });
        return this;
    }

    protected Emitter onEvent(final String name, final Object... args) {
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                SocketIOClient.super.emit(name, args);
            }
        });
        return this;
    }

    protected Emitter onEvent(final String name, final Ack ack, final Object... args) {
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                SocketIOClient.super.emit(name, ack, args);
            }
        });
        return this;
    }

    protected void onAck(final Long ackId, final Object... data) {
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                AckCallback<Object[]> callback = ackEntry.removeCallback(ackId);
                if (callback != null) {
                    callback.onSuccess(data);
                }
            }
        });
    }

    protected SocketIOClient option(SocketOption option) throws URISyntaxException, SSLException {

        checkUri(option.getUrl(), option.getPath(), option.getQuery());

        this.reconnectMax = option.getReconnectionAttempts();
        this.reconnection = option.isReconnection();
        this.reconnectionDelay = option.getReconnectionDelay();
        this.pingTimeout = option.getPingTimeout();
        this.pingInterval = option.getPingInterval();

        return this;
    }

    protected SocketIOClient bootstrap(Bootstrap bootstrap) {
        this.bootstrap = bootstrap;
        return this;
    }

    protected SocketIOClient decoder(PacketDecoder decoder) {
        this.decoder = decoder;
        return this;
    }

    protected SocketIOClient encoder(PacketEncoder encoder) {
        this.encoder = encoder;
        return this;
    }

    protected SocketIOClient scheduler(HashedWheelScheduler scheduler) {
        this.scheduler = scheduler;
        return this;
    }

    protected SocketIOClient executorService(ExecutorService executorService) {
        this.executorService = executorService;
        return this;
    }

    protected void update(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    protected SocketIOClient init() {
        initHandler();

        final SocketIOClient me = this;
        bootstrap.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ChannelPipeline channelPipeline = ch.pipeline();
                if (sslCtx != null) {
                    channelPipeline.addLast(sslCtx.newHandler(ch.alloc(), host, port));
                }
                channelPipeline.addLast(
                        new LoggingHandler(LogLevel.DEBUG),
                        new HttpClientCodec(),
                        new HttpObjectAggregator(8192),
                        WebSocketClientCompressionHandler.INSTANCE,
                        new SocketIODecoderHandler(
                                WebSocketClientHandshakerFactory.newHandshaker(
                                        uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders()),
                                me,
                                decoder),
                        new SocketIoEncoderHandler(encoder)
                );
            }
        });

        state.set(SOCKET_STATE.READY_TO_CONNECT);

        return this;
    }

    protected void parseHeader(FullHttpResponse response) {
        Iterator<Map.Entry<String, String>> entryIterator = response.headers().iteratorAsString();
        while (entryIterator.hasNext()) {
            Map.Entry<String, String> next = entryIterator.next();
            headers.put(next.getKey(), next.getValue());
        }
    }

    protected void setLastBinaryPacket(Packet lastBinaryPacket) {
        this.lastBinaryPacket = lastBinaryPacket;
    }

    protected Packet getLastBinaryPacket() {
        return lastBinaryPacket;
    }

    private void connect0() {
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                ChannelFuture connect = bootstrap.connect(host, port);
                connect.addListener(new GenericFutureListener<Future<? super Void>>() {
                    @Override
                    public void operationComplete(Future<? super Void> future) throws Exception {
                        if (!future.isSuccess()) {
                            clearScheduler();

                            state.set(SOCKET_STATE.RE_CONNECTING);

                            SocketIOClient.this.onEvent(EventType.EVENT_CONNECT_ERROR, future.cause());

                            if (reconnection && (reconnectMax == 0 || tryReconnect.get() < reconnectMax)) {
                                schedule(SCHEDULE_KEY.RECONNECT, reconnectTask, reconnectionDelay, TimeUnit.MILLISECONDS);
                                return;
                            }
                            state.set(SOCKET_STATE.DISCONNECTED);
                        }
                    }
                });
                channel = connect.channel();

            }
        });
    }

    private void disconnect0() {
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                clearScheduler();
                if (channel != null && channel.isActive()) {
                    channel.close();
                }
            }
        });
    }

    private void initHandler() {
        off();
        on(EventType.EVENT_OPEN, onOpenListener);
        on(EventType.EVENT_ACTIVE, onActiveListener);
        on(EventType.EVENT_DISCONNECT, onDisconnectListener);
        on(EventType.EVENT_KICK, onKickListener);
        on(EventType.EVENT_PONG, onPongListener);
    }

    private void checkUri(String url, String path, Map<String, String> query) throws URISyntaxException, SSLException {
        URI tempUri = new URI(url);
        String tempUriPath = tempUri.getPath();

        if (tempUriPath == null || "".equals(tempUriPath) || "/".equals(tempUriPath)) {
            tempUriPath = path;
        }
        if (tempUriPath.charAt(tempUriPath.length() - 1) != '/') {
            tempUriPath += '/';
        }

        QueryStringEncoder queryStringEncoder = new QueryStringEncoder(tempUriPath);
        QueryStringDecoder queryStringDecoder = new QueryStringDecoder(tempUri);
        Map<String, List<String>> tempUriParam = queryStringDecoder.parameters();

        Iterator<Map.Entry<String, List<String>>> tempUriParamIterator = tempUriParam.entrySet().iterator();
        while (tempUriParamIterator.hasNext()) {
            Map.Entry<String, List<String>> next = tempUriParamIterator.next();
            if (next.getValue() != null && !next.getValue().isEmpty()) {
                query.put(next.getKey(), next.getValue().get(0));
            }
        }

        // only support websocket
        query.put("EIO", "3");
        query.put("transport", "websocket");

        Iterator<Map.Entry<String, String>> queryParamIterator = query.entrySet().iterator();
        while (queryParamIterator.hasNext()) {
            Map.Entry<String, String> next = queryParamIterator.next();
            queryStringEncoder.addParam(next.getKey(), next.getValue());
        }

        this.scheme = tempUri.getScheme() == null ? "ws" : tempUri.getScheme();
        this.host = tempUri.getHost() == null ? "127.0.0.1" : tempUri.getHost();
        this.ssl = false;
        if (tempUri.getPort() == -1) {
            if ("ws".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) {
                port = 80;
            } else if ("wss".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                port = 443;
                ssl = true;
            } else {
                throw new IllegalArgumentException("protocol not support!");
            }
        } else {
            port = tempUri.getPort();
        }

        URI pathParamUri = queryStringEncoder.toUri();
        this.uri = new URI(scheme, null, host, tempUri.getPort(), pathParamUri.getPath(), pathParamUri.getQuery(), null);

        logger.info("URI=" + uri.toString());
        if (ssl) {
            sslCtx = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        }
    }

    private void clearScheduler() {
        // clear scheduler
        Iterator<String> iterator = keyList.iterator();
        while (iterator.hasNext()) {
            scheduler.cancel(iterator.next());
            iterator.remove();
        }
    }

    private void sendPing() {
        Packet packet = new Packet(PacketType.PING);
        packet.setName("ping");
        ctx.channel().writeAndFlush(packet);

        lastPingTimestamp = System.currentTimeMillis();
        onEvent(EventType.EVENT_PING, null);

        schedule(SCHEDULE_KEY.PING_TIMEOUT, pingTimeoutTask, pingTimeout, TimeUnit.MILLISECONDS);
    }

    private void scheduleAckTimeout(final Long ackId, AckCallback<?> callback) {
        if (callback.getTimeout() == -1) {
            return;
        }

        schedule(SCHEDULE_KEY.ACK_TIMEOUT, new Runnable() {
            @Override
            public void run() {
                AckCallback<?> cb = ackEntry.removeCallback(ackId);
                if (cb != null) {
                    cb.onTimeout();
                }
            }
        }, callback.getTimeout(), TimeUnit.SECONDS);
    }

    private void schedule(SCHEDULE_KEY key, Runnable runnable, long delay) {
        schedule(key, runnable, delay, TimeUnit.SECONDS);
    }

    private void schedule(SCHEDULE_KEY key, Runnable runnable, long delay, TimeUnit timeUnit) {
        String keyString = scheduleKey(key.name());
        keyList.add(keyString);
        scheduler.scheduleCallback(ctx, keyString, runnable, delay, timeUnit);
    }

    private void cancel(SCHEDULE_KEY key) {
        String keyString = scheduleKey(key.name());
        keyList.remove(keyString);
        scheduler.cancel(keyString);
    }

    private String scheduleKey(String key) {
        return params.get("sid") + ":" + key;
    }

    private Runnable pingTask = new Runnable() {
        @Override
        public void run() {
            sendPing();
        }
    };

    private Runnable pingTimeoutTask = new Runnable() {
        @Override
        public void run() {
            long duration = System.currentTimeMillis() - lastPingTimestamp;
            logger.debug("ping timeout for [" + duration + "] ms, will reconnect.");

            SocketIOClient.this.onEvent(EventType.EVENT_PING_TIMEOUT, duration);

            state.set(SOCKET_STATE.PING_TIMEOUT);
            disconnect0();
        }
    };

    private Runnable reconnectTask = new Runnable() {
        @Override
        public void run() {
            int i = tryReconnect.incrementAndGet();

            logger.info("try to reconnect " + i + "/" + reconnectMax + ".");

            SocketIOClient.this.onEvent(EventType.EVENT_RECONNECT, i);
            connect0();
        }
    };

    private Emitter.Listener onPongListener = new Emitter.Listener() {
        @Override
        public void call(Object... data) {
            cancel(SCHEDULE_KEY.PING_TIMEOUT);

            long duration = System.currentTimeMillis() - lastPingTimestamp;
            logger.debug("pong cost [" + duration + "] ms");

            schedule(SCHEDULE_KEY.PING, pingTask, pingInterval, TimeUnit.MILLISECONDS);
        }
    };

    private Emitter.Listener onOpenListener = new Emitter.Listener() {
        @Override
        public void call(Object... data) {
            state.set(SOCKET_STATE.CONNECTED);

            tryReconnect.set(0);

            JSONObject json = ((JSONObject) data[0]);
            for (Iterator<String> it = json.keys(); it.hasNext(); ) {
                String key = it.next();
                params.put(key, json.opt(key));
            }

            sid = (String) params.get("sid");
            logger.info(sid);

            sendPing();
        }
    };

    private Emitter.Listener onActiveListener = new Emitter.Listener() {
        @Override
        public void call(Object... data) {
            logger.info("tcp connect success, waiting for websocket handshake!");
        }
    };

    private Emitter.Listener onDisconnectListener = new Emitter.Listener() {
        @Override
        public void call(Object... data) {
            logger.info("tcp connection closed!");
            clearScheduler();

            if (state.get() != SOCKET_STATE.DISCONNECTING) {
                state.set(SOCKET_STATE.RE_CONNECTING);

                if (reconnection && (reconnectMax == 0 || tryReconnect.get() < reconnectMax)) {
                    schedule(SCHEDULE_KEY.RECONNECT, reconnectTask, reconnectionDelay, TimeUnit.MILLISECONDS);
                    return;
                }
            }
            state.set(SOCKET_STATE.DISCONNECTED);
        }
    };

    private Emitter.Listener onKickListener = new Emitter.Listener() {
        @Override
        public void call(Object... data) {
            logger.info("kick out by server, will close connection.");

            state.set(SOCKET_STATE.DISCONNECTING);
            disconnect0();
        }
    };
}
