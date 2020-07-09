package com.moesrc.socketio;

public interface EventType {

    String EVENT_TCP_CONNECT = "tcp_connect";
    String EVENT_TCP_DISCONNECT = "tcp_disconnect";
    String EVENT_CONNECT = "connect";
    String EVENT_ACK = "ack";
    String EVENT_PING = "ping";
    String EVENT_PONG = "pong";
    String EVENT_OPEN = "open";
    String EVENT_DISCONNECT = "disconnect";
    String EVENT_KICK = "kick";
    String EVENT_RECONNECT = "reconnect";
    String EVENT_RECONNECT_ATTEMPT = "reconnect_attempt";
    String EVENT_RECONNECTING = "reconnecting";
    String EVENT_CONNECT_ERROR = "connect_error";
    String EVENT_RECONNECT_ERROR = "reconnect_error";
    String EVENT_RECONNECT_FAILED = "reconnect_failed";
    String EVENT_ERROR = "error";
    String EVENT_CONNECT_TIMEOUT = "connect_timeout";
    String EVENT_PING_TIMEOUT = "ping_timeout";

}
