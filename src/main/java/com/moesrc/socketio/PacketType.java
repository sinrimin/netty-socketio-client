package com.moesrc.socketio;

public enum PacketType {

    OPEN(0), CLOSE(1), PING(2), PONG(3), MESSAGE(4), UPGRADE(5), NOOP(6),

    CONNECT(0, true), DISCONNECT(1, true), EVENT(2, true), ACK(3, true), ERROR(4, true), BINARY_EVENT(5, true), BINARY_ACK(6, true);

    public static final PacketType[] VALUES = values();
    private final int value;
    private final boolean inner;

    PacketType(int value) {
        this(value, false);
    }

    PacketType(int value, boolean inner) {
        this.value = value;
        this.inner = inner;
    }

    public int getValue() {
        return value;
    }

    public static PacketType valueOf(int value) {
        for (PacketType type : VALUES) {
            if (type.getValue() == value && !type.inner) {
                return type;
            }
        }
        throw new IllegalStateException();
    }

    public static PacketType valueOfInner(int value) {
        for (PacketType type : VALUES) {
            if (type.getValue() == value && type.inner) {
                return type;
            }
        }
        throw new IllegalArgumentException("Can't parse " + value);
    }

}
