package com.moesrc.socketio;

import io.netty.channel.*;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.util.CharsetUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;

public class SocketIODecoderHandler extends SimpleChannelInboundHandler<Object> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(SocketIODecoderHandler.class);

    private final WebSocketClientHandshaker handshaker;
    private ChannelPromise handshakeFuture;
    private SocketIOClient client;
    private PacketDecoder decoder;

    public SocketIODecoderHandler(WebSocketClientHandshaker handshaker, SocketIOClient client, PacketDecoder decoder) {
        this.handshaker = handshaker;
        this.client = client;
        this.decoder = decoder;
    }

    public ChannelFuture handshakeFuture() {
        return handshakeFuture;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        handshakeFuture = ctx.newPromise();
        client.update(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        handshaker.handshake(ctx.channel());
        client.onEvent(EventType.EVENT_ACTIVE, null);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        client.onEvent(EventType.EVENT_DISCONNECT, null);
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        Channel ch = ctx.channel();
        if (!handshaker.isHandshakeComplete()) {
            try {
                handshaker.finishHandshake(ch, (FullHttpResponse) msg);
                client.parseHeader((FullHttpResponse) msg);
                handshakeFuture.setSuccess();
            } catch (WebSocketHandshakeException e) {
                logger.error("failed to connect", e.getMessage(), e);
                handshakeFuture.setFailure(e);
                throw e;
            }
            return;
        }

        if (msg instanceof FullHttpResponse) {
            FullHttpResponse response = (FullHttpResponse) msg;
            throw new IllegalStateException(
                    "Unexpected FullHttpResponse (getStatus=" + response.status() +
                            ", content=" + response.content().toString(CharsetUtil.UTF_8) + ')');
        }

        WebSocketFrame frame = (WebSocketFrame) msg;
        final Packet packet = decoder.decodePackets(client, frame.content());
        logger.debug("received " + packet.toString());

        if (packet.getSubType() == PacketType.ACK
                || packet.getSubType() == PacketType.BINARY_ACK) {
            client.onAck(packet.getAckId(), toArray(packet.getData()));
        } else {
            if (packet.getAckId() != null) {
                client.onEvent(packet.getName(), new Emitter.Ack() {
                    @Override
                    public void send(Object... obj) {
                        Packet ackPacket = new Packet(PacketType.MESSAGE);
                        ackPacket.setSubType(PacketType.ACK);
                        ackPacket.setAckId(packet.getAckId());
                        ackPacket.setData(Arrays.asList(obj));
                        client.emit(ackPacket);
                    }
                }, toArray(packet.getData()));
                return;
            }
            client.onEvent(packet.getName(), toArray(packet.getData()));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error(cause.getMessage(), cause);
        String eventType = EventType.EVENT_ERROR;
        if (!handshakeFuture.isDone()) {
            handshakeFuture.setFailure(cause);
            eventType = EventType.EVENT_CONNECT_ERROR;
        }
        client.onEvent(eventType, cause);
        ctx.close();
    }

    private static Object[] toArray(Object data) {
        if (data instanceof JSONArray) {
            JSONArray array = (JSONArray) data;
            int length = array.length();
            Object[] result = new Object[length];
            for (int i = 0; i < length; i++) {
                Object v;
                try {
                    v = array.get(i);
                } catch (JSONException e) {
                    logger.error("An error occured while retrieving data from JSONArray", e);
                    v = null;
                }
                result[i] = JSONObject.NULL.equals(v) ? null : v;
            }
            return result;
        }
        Object[] result = new Object[1];
        result[0] = data;
        return result;
    }
}