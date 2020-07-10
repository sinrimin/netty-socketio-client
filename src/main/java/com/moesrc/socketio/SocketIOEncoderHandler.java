package com.moesrc.socketio;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

import java.util.logging.Logger;

@ChannelHandler.Sharable
public class SocketIOEncoderHandler extends ChannelOutboundHandlerAdapter {

    private static final Logger logger = Logger.getLogger(SocketIOEncoderHandler.class.getName());

    private PacketEncoder encoder;

    public SocketIOEncoderHandler(PacketEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof Packet)) {
            super.write(ctx, msg, promise);
            return;
        }

        Packet packet = (Packet) msg;

        ByteBuf out = encoder.allocateBuffer(ctx.alloc());
        encoder.encodePacket(packet, out, ctx.alloc(), true);
        WebSocketFrame res = new TextWebSocketFrame(out);

        if (out.isReadable()) {
            logger.fine("send " + packet.toString());
            ctx.channel().writeAndFlush(res);
        } else {
            out.release();
        }

        for (ByteBuf buf : packet.getAttachments()) {
            ByteBuf outBuf = encoder.allocateBuffer(ctx.alloc());
            outBuf.writeByte(4);
            outBuf.writeBytes(buf);
            ctx.channel().writeAndFlush(new BinaryWebSocketFrame(outBuf));
        }

    }
}
