package com.moesrc.socketio;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.json.JSONObject;

import javax.net.ssl.SSLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    private SocketIOEncoderHandler encoderHandler;
    private WebSocketClientCompressionHandler compressionHandler;
    private Channel channel;
    private ChannelHandlerContext ctx;

    // run state
    private AtomicReference<SOCKET_STATE> state = new AtomicReference<>(SOCKET_STATE.NEW);

    private URI uri;
    private String scheme;
    private String host;
    private int port;
    private boolean ssl = false;
    private SslContext sslCtx;
    private String sid;
    private int reconnectMax = Integer.MAX_VALUE;
    private boolean reconnection = true;
    private Backoff backoff = null;

    private long pingTimeout = 60000;
    private long pingInterval = 25000;
    private long lastPingTimestamp = 0L;

    private Packet lastBinaryPacket;

    private Map<String, String> headers = new HashMap<>();
    private AckEntry ackEntry = new AckEntry();
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

                backoff.reset();
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
        executorService.submit(new EventTask(this) {
            @Override
            public void task() {
                if (!isConnected()) {
                    SocketIOClient.this.onEvent(EventType.EVENT_ERROR, new SocketNotAvailableException("socket is not ready. current state is [" + state.get() + "]"));
                    return;
                }

                ctx.channel().writeAndFlush(packet);
            }
        });
        return this;
    }

    @Override
    public Emitter emit(final String name, final Object... args) {
        executorService.submit(new EventTask(this) {
            @Override
            public void task() {
                AckCallback callback = null;
                Object[] _args = args;
                Long ackId = null;
                if (args != null) {
                    int lastIndex = args.length - 1;
                    if (args.length > 0 && args[lastIndex] instanceof AckCallback) {
                        _args = new Object[lastIndex];
                        System.arraycopy(args, 0, _args, 0, lastIndex);
                        callback = (AckCallback) args[lastIndex];
                    }
                } else {
                    _args = new Object[0];
                }

                if (!isConnected()) {
                    if (callback != null) {
                        callback.onTimeout();
                    }
                    SocketIOClient.this.onEvent(EventType.EVENT_ERROR, new SocketNotAvailableException("socket is not ready. current state is [" + state.get() + "]"));
                    return;
                }

                Packet packet = new Packet(PacketType.MESSAGE);
                packet.setSubType(PacketType.EVENT);
                packet.setName(name);
                packet.setData(Arrays.asList(_args));
                if (callback != null) {
                    ackId = ackEntry.addAckCallback(callback);
                    packet.setAckId(ackId);
                    scheduleAckTimeout(ackId, callback);
                }
                ctx.channel().writeAndFlush(packet);
            }
        });
        return this;
    }

    public String getSid() {
        return sid;
    }

    protected Emitter onEvent(final String name, final Object... args) {
        executorService.submit(new EventTask(this) {
            @Override
            public void task() {
                SocketIOClient.super.emit(name, args);
            }
        });
        return this;
    }

    protected void onAck(final Long ackId, final Object... data) {
        executorService.submit(new EventTask(this) {
            @Override
            public void task() {
                AckCallback callback = ackEntry.removeCallback(ackId);
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
        this.pingTimeout = option.getPingTimeout();
        this.pingInterval = option.getPingInterval();
        this.backoff = new Backoff()
                .setMin(option.getReconnectionDelay())
                .setMax(option.getReconnectionDelayMax())
                .setJitter(option.getRandomizationFactor());

        if (ssl) {
            if (option.getSslContext() != null) {
                this.sslCtx = option.getSslContext();
            } else {
                this.sslCtx = SslContextBuilder.forClient().build();
            }
        }

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

    protected SocketIOClient encoderHandler(SocketIOEncoderHandler encoderHandler) {
        this.encoderHandler = encoderHandler;
        return this;
    }

    protected SocketIOClient compressionHandler(WebSocketClientCompressionHandler compressionHandler) {
        this.compressionHandler = compressionHandler;
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

    protected void update(ChannelHandlerContext ctx, ChannelPromise handshakeFuture) {
        this.ctx = ctx;
        handshakeFuture.addListener(handshakeListener);
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
                        // new LoggingHandler(LogLevel.DEBUG),
                        new HttpClientCodec(),
                        new HttpObjectAggregator(64 * 1024),
                        compressionHandler,
                        new WebSocketClientProtocolHandler(
                                WebSocketClientProtocolConfig.newBuilder()
                                        .webSocketUri(uri)
                                        .allowExtensions(true)
                                        .handshakeTimeoutMillis(2000)
                                        .build()
                        ),
                        new SocketIODecoderHandler(me, decoder),
                        encoderHandler
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
        executorService.submit(new EventTask(this) {
            @Override
            public void task() {
                ChannelFuture connect = bootstrap.connect(host, port);
                connect.addListener(connectListener);
                channel = connect.channel();
            }
        });
    }

    private void disconnect0() {
        executorService.submit(new EventTask(this) {
            @Override
            public void task() {
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
        on(EventType.EVENT_TCP_CONNECT, onTcpConnectListener);
        on(EventType.EVENT_TCP_DISCONNECT, onTcpDisconnectListener);
        on(EventType.EVENT_KICK, onKickListener);
        on(EventType.EVENT_PONG, onPongListener);
    }

    private void checkUri(String url, String path, Map<String, String> query) throws URISyntaxException {
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

    private void scheduleAckTimeout(final Long ackId, AckCallback callback) {
        if (callback.getTimeout() == -1) {
            return;
        }

        schedule(SCHEDULE_KEY.ACK_TIMEOUT, new Runnable() {
            @Override
            public void run() {
                AckCallback cb = ackEntry.removeCallback(ackId);
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

    private boolean reconnect() {
        if (reconnection && backoff.getAttempts() < reconnectMax) {
            state.set(SOCKET_STATE.RE_CONNECTING);
            long duration = backoff.duration();
            SocketIOClient.this.onEvent(EventType.EVENT_RECONNECT_ATTEMPT, backoff.getAttempts());
            schedule(SCHEDULE_KEY.RECONNECT, reconnectTask, duration, TimeUnit.MILLISECONDS);
            return true;
        }
        if (reconnection) {
            SocketIOClient.this.onEvent(EventType.EVENT_RECONNECT_FAILED, backoff.getAttempts());
        }
        return false;
    }

    private Runnable reconnectTask = new Runnable() {
        @Override
        public void run() {

            logger.info("try to reconnect " + backoff.getAttempts() + "/" + reconnectMax + ".");

            SocketIOClient.this.onEvent(EventType.EVENT_RECONNECTING, backoff.getAttempts());
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
            JSONObject json = ((JSONObject) data[0]);
            for (Iterator<String> it = json.keys(); it.hasNext(); ) {
                String key = it.next();
                params.put(key, json.opt(key));
            }

            sid = (String) params.get("sid");
            Integer pingInterval = (Integer) params.get("pingInterval");
            if (pingInterval != null && pingInterval > 0) {
                SocketIOClient.this.pingInterval = pingInterval;
            }
            Integer pingTimeout = (Integer) params.get("pingTimeout");
            if (pingTimeout != null && pingTimeout > 0) {
                SocketIOClient.this.pingTimeout = pingTimeout;
            }

            logger.info("sessionId={}, pingInterval={}, pingTimeout={}", sid, SocketIOClient.this.pingInterval, SocketIOClient.this.pingTimeout);
            sendPing();
        }
    };

    private Emitter.Listener onTcpConnectListener = new Emitter.Listener() {
        @Override
        public void call(Object... data) {
            logger.info("tcp connect success, waiting for websocket handshake!");
        }
    };

    private Emitter.Listener onTcpDisconnectListener = new Emitter.Listener() {
        @Override
        public void call(Object... data) {
            logger.info("tcp connection closed!");
            clearScheduler();

            if (state.get() == SOCKET_STATE.DISCONNECTING || !reconnect()) {
                state.set(SOCKET_STATE.DISCONNECTED);
            }
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

    private GenericFutureListener connectListener = new GenericFutureListener<Future<? super Void>>() {
        @Override
        public void operationComplete(Future<? super Void> future) throws Exception {
            if (!future.isSuccess()) {
                clearScheduler();

                if (SOCKET_STATE.RE_CONNECTING == state.get()) {
                    SocketIOClient.this.onEvent(EventType.EVENT_RECONNECT_ERROR, future.cause());
                }

                if (SOCKET_STATE.CONNECTING == state.get()) {
                    SocketIOClient.this.onEvent(EventType.EVENT_CONNECT_ERROR, future.cause());
                }

                if (future.cause() instanceof ConnectTimeoutException) {
                    SocketIOClient.this.onEvent(EventType.EVENT_CONNECT_TIMEOUT, future.cause());
                }

                if (!reconnect()) {
                    state.set(SOCKET_STATE.DISCONNECTED);
                }
            }
        }
    };

    private GenericFutureListener handshakeListener = new GenericFutureListener<Future<? super Void>>() {
        @Override
        public void operationComplete(Future<? super Void> future) {
            if (!future.isSuccess()) {
                SocketIOClient.this.onEvent(EventType.EVENT_CONNECT_TIMEOUT, future.cause());
                if (SOCKET_STATE.RE_CONNECTING == state.get()) {
                    SocketIOClient.this.onEvent(EventType.EVENT_RECONNECT_ERROR, future.cause());
                }
                if (SOCKET_STATE.CONNECTING == state.get()) {
                    SocketIOClient.this.onEvent(EventType.EVENT_CONNECT_ERROR, future.cause());
                }
            } else {
                if (SOCKET_STATE.RE_CONNECTING == state.get()) {
                    SocketIOClient.this.onEvent(EventType.EVENT_RECONNECT, backoff.getAttempts());
                }
                state.set(SOCKET_STATE.CONNECTED);

                backoff.reset();
            }
        }
    };

    private static abstract class EventTask implements Runnable {
        private final SocketIOClient client;

        public EventTask(SocketIOClient client) {
            this.client = client;
        }

        @Override
        public final void run() {
            try {
                task();
            } catch (Exception e) {
                logger.error("event task got an exception", e);
                client.onEvent(EventType.EVENT_ERROR, e);
            }
        }

        public abstract void task();
    }
}
