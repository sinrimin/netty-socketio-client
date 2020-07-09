package com.moesrc.socketio;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

import java.util.logging.Logger;

@ChannelHandler.Sharable
public class SocketIoEncoderHandler extends ChannelOutboundHandlerAdapter {

    private static final Logger logger = Logger.getLogger(SocketIoEncoderHandler.class.getName());

    private PacketEncoder encoder;

    public SocketIoEncoderHandler(PacketEncoder encoder) {
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

        if (out.isReadable()) {
            logger.fine("send " + packet.toString());
            ctx.channel().writeAndFlush(out);
        } else {
            out.release();
        }

        for (ByteBuf buf : packet.getAttachments()) {
            ByteBuf outBuf = encoder.allocateBuffer(ctx.alloc());
            outBuf.writeByte(4);
            outBuf.writeBytes(buf);
            ctx.channel().writeAndFlush(outBuf);
        }

    }
}
