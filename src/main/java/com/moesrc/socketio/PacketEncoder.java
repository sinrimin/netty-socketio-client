package com.moesrc.socketio;

import org.json.JSONArray;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.CharsetUtil;

public class PacketEncoder {

    private static final byte[] BINARY_HEADER = "b4".getBytes(CharsetUtil.UTF_8);
    private static final byte[] B64_DELIMITER = new byte[]{':'};
    private static final byte[] JSONP_HEAD = "___eio[".getBytes(CharsetUtil.UTF_8);
    private static final byte[] JSONP_START = "]('".getBytes(CharsetUtil.UTF_8);
    private static final byte[] JSONP_END = "');".getBytes(CharsetUtil.UTF_8);

    public ByteBuf allocateBuffer(ByteBufAllocator allocator) {
        return allocator.heapBuffer();
    }

    private void processUtf8(ByteBuf in, ByteBuf out, boolean jsonpMode) {
        while (in.isReadable()) {
            short value = (short) (in.readByte() & 0xFF);
            if (value >>> 7 == 0) {
                if (jsonpMode && (value == '\\' || value == '\'')) {
                    out.writeByte('\\');
                }
                out.writeByte(value);
            } else {
                out.writeByte(((value >>> 6) | 0xC0));
                out.writeByte(((value & 0x3F) | 0x80));
            }
        }
    }

    private byte toChar(int number) {
        return (byte) (number ^ 0x30);
    }

    static final char[] DigitTens = {'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '1', '1', '1', '1',
            '1', '1', '1', '1', '1', '1', '2', '2', '2', '2', '2', '2', '2', '2', '2', '2', '3', '3', '3',
            '3', '3', '3', '3', '3', '3', '3', '4', '4', '4', '4', '4', '4', '4', '4', '4', '4', '5', '5',
            '5', '5', '5', '5', '5', '5', '5', '5', '6', '6', '6', '6', '6', '6', '6', '6', '6', '6', '7',
            '7', '7', '7', '7', '7', '7', '7', '7', '7', '8', '8', '8', '8', '8', '8', '8', '8', '8', '8',
            '9', '9', '9', '9', '9', '9', '9', '9', '9', '9',};

    static final char[] DigitOnes = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '0', '1', '2', '3',
            '4', '5', '6', '7', '8', '9', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '0', '1', '2',
            '3', '4', '5', '6', '7', '8', '9', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '0', '1',
            '2', '3', '4', '5', '6', '7', '8', '9', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '0',
            '1', '2', '3', '4', '5', '6', '7', '8', '9', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',};

    static final char[] digits = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e',
            'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x',
            'y', 'z'};

    static final int[] sizeTable = {9, 99, 999, 9999, 99999, 999999, 9999999, 99999999, 999999999,
            Integer.MAX_VALUE};

    // Requires positive x
    static int stringSize(long x) {
        for (int i = 0; ; i++)
            if (x <= sizeTable[i])
                return i + 1;
    }

    static void getChars(long i, int index, byte[] buf) {
        long q, r;
        int charPos = index;
        byte sign = 0;

        if (i < 0) {
            sign = '-';
            i = -i;
        }

        // Generate two digits per iteration
        while (i >= 65536) {
            q = i / 100;
            // really: r = i - (q * 100);
            r = i - ((q << 6) + (q << 5) + (q << 2));
            i = q;
            buf[--charPos] = (byte) DigitOnes[(int) r];
            buf[--charPos] = (byte) DigitTens[(int) r];
        }

        // Fall thru to fast mode for smaller numbers
        // assert(i <= 65536, i);
        for (; ; ) {
            q = (i * 52429) >>> (16 + 3);
            r = i - ((q << 3) + (q << 1)); // r = i-(q*10) ...
            buf[--charPos] = (byte) digits[(int) r];
            i = q;
            if (i == 0)
                break;
        }
        if (sign != 0) {
            buf[--charPos] = sign;
        }
    }

    public static byte[] toChars(long i) {
        int size = (i < 0) ? stringSize(-i) + 1 : stringSize(i);
        byte[] buf = new byte[size];
        getChars(i, size, buf);
        return buf;
    }

    public static byte[] longToBytes(long number) {
        // TODO optimize
        int length = (int) (Math.log10(number) + 1);
        byte[] res = new byte[length];
        int i = length;
        while (number > 0) {
            res[--i] = (byte) (number % 10);
            number = number / 10;
        }
        return res;
    }

    public void encodePacket(Packet packet, ByteBuf buffer, ByteBufAllocator allocator, boolean binary) throws IOException {
        ByteBuf buf = buffer;
        if (!binary) {
            buf = allocateBuffer(allocator);
        }
        byte type = toChar(packet.getType().getValue());
        buf.writeByte(type);

        try {
            switch (packet.getType()) {

                case PONG: {
                    buf.writeBytes(packet.getData().toString().getBytes(CharsetUtil.UTF_8));
                    break;
                }

                case OPEN: {
                    JSONArray values = new JSONArray();

                    List<Object> args = packet.getData();
                    for (Object obj : args) {
                        values.put(obj);
                    }

                    buf.writeCharSequence(values.toString(), Charset.defaultCharset());
                    break;
                }

                case MESSAGE: {

                    ByteBuf encBuf = null;

                    if (packet.getSubType() == PacketType.ERROR) {
                        encBuf = allocateBuffer(allocator);

                        JSONArray values = new JSONArray();

                        List<Object> args = packet.getData();
                        for (Object obj : args) {
                            values.put(obj);
                        }

                        encBuf.writeCharSequence(values.toString(), Charset.defaultCharset());
                    }

                    if (packet.getSubType() == PacketType.EVENT
                            || packet.getSubType() == PacketType.ACK) {

                        JSONArray values = new JSONArray();
                        if (packet.getSubType() == PacketType.EVENT) {
                            values.put(packet.getName());
                        }

                        encBuf = allocateBuffer(allocator);

                        List<Object> args = packet.getData();
                        for (Object obj : args) {
                            values.put(obj);
                        }

                        encBuf.writeCharSequence(values.toString(), Charset.defaultCharset());

//                        if (!jsonSupport.getArrays().isEmpty()) {
//                            packet.initAttachments(jsonSupport.getArrays().size());
//                            for (byte[] array : jsonSupport.getArrays()) {
//                                packet.addAttachment(Unpooled.wrappedBuffer(array));
//                            }
//                            packet.setSubType(packet.getSubType() == PacketType.ACK
//                                    ? PacketType.BINARY_ACK : PacketType.BINARY_EVENT);
//                        }
                    }

                    byte subType = toChar(packet.getSubType().getValue());
                    buf.writeByte(subType);

                    if (packet.hasAttachments()) {
                        byte[] ackId = toChars(packet.getAttachments().size());
                        buf.writeBytes(ackId);
                        buf.writeByte('-');
                    }

                    if (packet.getSubType() == PacketType.CONNECT) {
                        if (!packet.getNsp().isEmpty()) {
                            buf.writeBytes(packet.getNsp().getBytes(CharsetUtil.UTF_8));
                        }
                    } else {
                        if (!packet.getNsp().isEmpty()) {
                            buf.writeBytes(packet.getNsp().getBytes(CharsetUtil.UTF_8));
                            buf.writeByte(',');
                        }
                    }

                    if (packet.getAckId() != null) {
                        byte[] ackId = toChars(packet.getAckId());
                        buf.writeBytes(ackId);
                    }

                    if (encBuf != null) {
                        buf.writeBytes(encBuf);
                        encBuf.release();
                    }

                    break;
                }
            }
        } finally {
            // we need to write a buffer in any case
            if (!binary) {
                buffer.writeByte(0);
                int length = buf.writerIndex();
                buffer.writeBytes(longToBytes(length));
                buffer.writeByte(0xff);
                buffer.writeBytes(buf);

                buf.release();
            }
        }
    }

    public static int find(ByteBuf buffer, ByteBuf searchValue) {
        for (int i = buffer.readerIndex(); i < buffer.readerIndex() + buffer.readableBytes(); i++) {
            if (isValueFound(buffer, i, searchValue)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isValueFound(ByteBuf buffer, int index, ByteBuf search) {
        for (int i = 0; i < search.readableBytes(); i++) {
            if (buffer.getByte(index + i) != search.getByte(i)) {
                return false;
            }
        }
        return true;
    }

}
