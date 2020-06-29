package com.moesrc.socketio;

public interface EventType {

    String EVENT_ACTIVE = "active";
    String EVENT_ACK = "ack";
    String EVENT_PING = "ping";
    String EVENT_PONG = "pong";
    String EVENT_OPEN = "open";
    String EVENT_CONNECT = "connect";
    String EVENT_DISCONNECT = "disconnect";
    String EVENT_KICK = "kick";
    String EVENT_RECONNECT = "reconnect";
    String EVENT_CONNECT_ERROR = "connect_error";
    String EVENT_ERROR = "error";
    String EVENT_PING_TIMEOUT = "ping_timeout";

}
