package com.moesrc.socketio;


import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.base64.Base64;
import io.netty.util.CharsetUtil;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedList;

public class PacketDecoder {

    private final static ByteBuf QUOTES = Unpooled.copiedBuffer("\"", CharsetUtil.UTF_8);
    private final static UTF8CharsScanner utf8scanner = new UTF8CharsScanner();

    private boolean isStringPacket(ByteBuf content) {
        return content.getByte(content.readerIndex()) == 0x0;
    }

    // fastest way to parse chars to int
    private long readLong(ByteBuf chars, int length) {
        long result = 0;
        for (int i = chars.readerIndex(); i < chars.readerIndex() + length; i++) {
            int digit = ((int) chars.getByte(i) & 0xF);
            for (int j = 0; j < chars.readerIndex() + length - 1 - i; j++) {
                digit *= 10;
            }
            result += digit;
        }
        chars.readerIndex(chars.readerIndex() + length);
        return result;
    }

    private PacketType readType(ByteBuf buffer) {
        int typeId = buffer.readByte() & 0xF;
        return PacketType.valueOf(typeId);
    }

    private PacketType readInnerType(ByteBuf buffer) {
        int typeId = buffer.readByte() & 0xF;
        return PacketType.valueOfInner(typeId);
    }

    private boolean hasLengthHeader(ByteBuf buffer) {
        for (int i = 0; i < Math.min(buffer.readableBytes(), 10); i++) {
            byte b = buffer.getByte(buffer.readerIndex() + i);
            if (b == (byte) ':' && i > 0) {
                return true;
            }
            if (b > 57 || b < 48) {
                return false;
            }
        }
        return false;
    }

    public Packet decodePackets(SocketIOClient client, ByteBuf buffer) throws IOException, JSONException {
        if (isStringPacket(buffer)) {
            // TODO refactor
            int maxLength = Math.min(buffer.readableBytes(), 10);
            int headEndIndex = buffer.bytesBefore(maxLength, (byte) -1);
            if (headEndIndex == -1) {
                headEndIndex = buffer.bytesBefore(maxLength, (byte) 0x3f);
            }
            int len = (int) readLong(buffer, headEndIndex);

            ByteBuf frame = buffer.slice(buffer.readerIndex() + 1, len);
            // skip this frame
            buffer.readerIndex(buffer.readerIndex() + 1 + len);
            return decode(client, frame);
        } else if (hasLengthHeader(buffer)) {
            // TODO refactor
            int lengthEndIndex = buffer.bytesBefore((byte) ':');
            int lenHeader = (int) readLong(buffer, lengthEndIndex);
            int len = utf8scanner.getActualLength(buffer, lenHeader);

            ByteBuf frame = buffer.slice(buffer.readerIndex() + 1, len);
            // skip this frame
            buffer.readerIndex(buffer.readerIndex() + 1 + len);
            return decode(client, frame);
        }
        return decode(client, buffer);
    }

    private String readString(ByteBuf frame) {
        return readString(frame, frame.readableBytes());
    }

    private String readString(ByteBuf frame, int size) {
        byte[] bytes = new byte[size];
        frame.readBytes(bytes);
        return new String(bytes, CharsetUtil.UTF_8);
    }

    private Packet decode(SocketIOClient client, ByteBuf frame) throws IOException, JSONException {
        if ((frame.getByte(0) == 'b' && frame.getByte(1) == '4')
                || frame.getByte(0) == 4 || frame.getByte(0) == 1) {
            return parseBinary(client, frame);
        }
        PacketType type = readType(frame);
        Packet packet = new Packet(type);
        packet.setSize(frame.writerIndex());

        if (type == PacketType.OPEN) {
            ByteBufInputStream in = new ByteBufInputStream(frame);
            packet.setName(EventType.EVENT_OPEN);
            packet.setData(new JSONObject(read(in)));
            return packet;
        }

        if (type == PacketType.PONG) {
            packet.setName(EventType.EVENT_PONG);
            packet.setData(readString(frame));
            return packet;
        }

        if (!frame.isReadable()) {
            return packet;
        }

        PacketType innerType = readInnerType(frame);
        packet.setSubType(innerType);

        parseHeader(frame, packet, innerType);
        parseBody(client, frame, packet);
        return packet;
    }

    private void parseHeader(ByteBuf frame, Packet packet, PacketType innerType) {
        int endIndex = frame.bytesBefore((byte) '[');
        if (endIndex <= 0) {
            return;
        }

        int attachmentsDividerIndex = frame.bytesBefore(endIndex, (byte) '-');
        boolean hasAttachments = attachmentsDividerIndex != -1;
        if (hasAttachments && (PacketType.BINARY_EVENT.equals(innerType)
                || PacketType.BINARY_ACK.equals(innerType))) {
            int attachments = (int) readLong(frame, attachmentsDividerIndex);
            packet.initAttachments(attachments);
            frame.readerIndex(frame.readerIndex() + 1);

            endIndex -= attachmentsDividerIndex + 1;
        }
        if (endIndex == 0) {
            return;
        }

        // TODO optimize
        boolean hasNsp = frame.bytesBefore(endIndex, (byte) ',') != -1;
        if (hasNsp) {
            String nspAckId = readString(frame, endIndex);
            String[] parts = nspAckId.split(",");
            String nsp = parts[0];
            packet.setNsp(nsp);
            if (parts.length > 1) {
                String ackId = parts[1];
                packet.setAckId(Long.valueOf(ackId));
            }
        } else {
            long ackId = readLong(frame, endIndex);
            packet.setAckId(ackId);
        }
    }

    private Packet parseBinary(SocketIOClient client, ByteBuf frame) throws IOException, JSONException {
        if (frame.getByte(0) == 1) {
            frame.readByte();
            int headEndIndex = frame.bytesBefore((byte) -1);
            int len = (int) readLong(frame, headEndIndex);
            ByteBuf oldFrame = frame;
            frame = frame.slice(oldFrame.readerIndex() + 1, len);
            oldFrame.readerIndex(oldFrame.readerIndex() + 1 + len);
        }

        if (frame.getByte(0) == 'b' && frame.getByte(1) == '4') {
            frame.readShort();
        } else if (frame.getByte(0) == 4) {
            frame.readByte();
        }

        Packet binaryPacket = client.getLastBinaryPacket();
        if (binaryPacket != null) {
            if (frame.getByte(0) == 'b' && frame.getByte(1) == '4') {
                binaryPacket.addAttachment(Unpooled.copiedBuffer(frame));
            } else {
                ByteBuf attachBuf = Base64.encode(frame);
                binaryPacket.addAttachment(Unpooled.copiedBuffer(attachBuf));
                attachBuf.release();
            }
            frame.readerIndex(frame.readerIndex() + frame.readableBytes());

            if (binaryPacket.isAttachmentsLoaded()) {
                LinkedList<ByteBuf> slices = new LinkedList<ByteBuf>();
                ByteBuf source = binaryPacket.getDataSource();
                for (int i = 0; i < binaryPacket.getAttachments().size(); i++) {
                    ByteBuf attachment = binaryPacket.getAttachments().get(i);
                    ByteBuf scanValue = Unpooled.copiedBuffer("{\"_placeholder\":true,\"num\":" + i + "}", CharsetUtil.UTF_8);
                    int pos = PacketEncoder.find(source, scanValue);
                    if (pos == -1) {
                        scanValue = Unpooled.copiedBuffer("{\"num\":" + i + ",\"_placeholder\":true}", CharsetUtil.UTF_8);
                        pos = PacketEncoder.find(source, scanValue);
                        if (pos == -1) {
                            throw new IllegalStateException("Can't find attachment by index: " + i + " in packet source");
                        }
                    }

                    ByteBuf prefixBuf = source.slice(source.readerIndex(), pos - source.readerIndex());
                    slices.add(prefixBuf);
                    slices.add(QUOTES);
                    slices.add(attachment);
                    slices.add(QUOTES);

                    source.readerIndex(pos + scanValue.readableBytes());
                }
                slices.add(source.slice());

                ByteBuf compositeBuf = Unpooled.wrappedBuffer(slices.toArray(new ByteBuf[slices.size()]));
                parseBody(client, compositeBuf, binaryPacket);
                client.setLastBinaryPacket(null);
                return binaryPacket;
            }
        }
        return new Packet(PacketType.MESSAGE);
    }

    private void parseBody(SocketIOClient client, ByteBuf frame, Packet packet) throws IOException, JSONException {
        if (packet.getType() == PacketType.MESSAGE) {
            if (packet.getSubType() == PacketType.CONNECT
                    || packet.getSubType() == PacketType.DISCONNECT) {
                packet.setNsp(readNamespace(frame));
            }

            if (packet.hasAttachments() && !packet.isAttachmentsLoaded()) {
                packet.setDataSource(Unpooled.copiedBuffer(frame));
                frame.readerIndex(frame.readableBytes());
                client.setLastBinaryPacket(packet);
            }

            if (packet.hasAttachments() && !packet.isAttachmentsLoaded()) {
                return;
            }

            if (packet.getSubType() == PacketType.CONNECT) {
                packet.setName(EventType.EVENT_CONNECT);
            }

            if (packet.getSubType() == PacketType.DISCONNECT) {
                packet.setName(EventType.EVENT_KICK);
            }

            if (packet.getSubType() == PacketType.ACK
                    || packet.getSubType() == PacketType.BINARY_ACK) {
                ByteBufInputStream in = new ByteBufInputStream(frame);
                packet.setName(EventType.EVENT_ACK);
                packet.setData(new JSONArray(read(in)));
            }

            if (packet.getSubType() == PacketType.EVENT
                    || packet.getSubType() == PacketType.BINARY_EVENT) {
                ByteBufInputStream in = new ByteBufInputStream(frame);
                JSONArray args = new JSONArray(read(in));
                String name = (String) args.remove(0);
                packet.setName(name);
                packet.setData(args);
            }
        }
    }

    private String readNamespace(ByteBuf frame) {
        /**
         * namespace post request with url queryString, like
         *  /message?a=1,
         *  /message,
         */
        int endIndex = frame.bytesBefore((byte) '?');
        if (endIndex > 0) {
            return readString(frame, endIndex);
        }
        endIndex = frame.bytesBefore((byte) ',');
        if (endIndex > 0) {
            return readString(frame, endIndex);
        }
        return readString(frame);
    }


    private String read(ByteBufInputStream bis) throws IOException, JSONException {
        BufferedReader streamReader = new BufferedReader(new InputStreamReader((InputStream) bis, "UTF-8"));
        StringBuilder responseStrBuilder = new StringBuilder();

        String inputStr;
        while ((inputStr = streamReader.readLine()) != null)
            responseStrBuilder.append(inputStr);
        return responseStrBuilder.toString();
    }
}
