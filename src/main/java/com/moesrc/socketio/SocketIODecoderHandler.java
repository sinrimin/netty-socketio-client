package com.moesrc.socketio;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Objects;

public class SocketIODecoderHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(SocketIODecoderHandler.class);

    private ChannelPromise handshakeFuture;
    private SocketIOClient client;
    private PacketDecoder decoder;

    public SocketIODecoderHandler(SocketIOClient client, PacketDecoder decoder) {
        this.client = client;
        this.decoder = decoder;
    }

    public ChannelPromise handshakeFuture() {
        return handshakeFuture;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        handshakeFuture = ctx.newPromise();
        client.update(ctx, handshakeFuture);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        client.onEvent(EventType.EVENT_TCP_CONNECT, null);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (handshakeFuture.isSuccess()) {
            client.onEvent(EventType.EVENT_DISCONNECT, null);
        }
        client.onEvent(EventType.EVENT_TCP_DISCONNECT, null);
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
        final Packet packet = decoder.decodePackets(client, frame.content());
        if (packet.hasAttachments() && !packet.isAttachmentsLoaded()) {
            return;
        }
        logger.debug("received " + packet.toString());

        if (packet.getSubType() == PacketType.ACK
                || packet.getSubType() == PacketType.BINARY_ACK) {
            client.onAck(packet.getAckId(), toArray(packet.getData()));
        } else {
            if (packet.getAckId() != null) {
                client.onEvent(packet.getName()
                        , toArray(packet.getData())
                        , new AckRequest() {
                            @Override
                            public void send(Object... obj) {
                                Packet ackPacket = new Packet(PacketType.MESSAGE);
                                ackPacket.setSubType(PacketType.ACK);
                                ackPacket.setAckId(packet.getAckId());
                                ackPacket.setData(Arrays.asList(obj));
                                client.emit(ackPacket);
                            }
                        });
                return;
            }
            client.onEvent(packet.getName(), toArray(packet.getData()));
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (Objects.equals(WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_TIMEOUT, evt)) {
            WebSocketHandshakeException cause = new WebSocketHandshakeException("handshake timed out");
            logger.error(cause);
            handshakeFuture.tryFailure(cause);
        }
        if (Objects.equals(WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE, evt)) {
            logger.debug("handshake complete!");
            handshakeFuture.trySuccess();
        }

        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error(cause.getMessage(), cause);
        if (!handshakeFuture.isDone()) {
            handshakeFuture.tryFailure(cause);
            ctx.close();
            return;
        }
        client.onEvent(EventType.EVENT_ERROR, cause);
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