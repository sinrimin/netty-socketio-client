package com.moesrc.socketio;

import java.util.LinkedHashMap;
import java.util.Map;

public class SocketOption {
    private Boolean reconnection = true;
    public Integer reconnectionAttempts = 5;
    public Long reconnectionDelay = 5000L;
    public Long pingTimeout = 60000L;
    public Long pingInterval = 25000L;
    public String url;
    public String path = "/socket.io/";
    public Map<String, String> query = new LinkedHashMap<>();

    public boolean isReconnection() {
        return reconnection;
    }

    public void setReconnection(boolean reconnection) {
        this.reconnection = reconnection;
    }

    public int getReconnectionAttempts() {
        return reconnectionAttempts;
    }

    public void setReconnectionAttempts(int reconnectionAttempts) {
        this.reconnectionAttempts = reconnectionAttempts;
    }

    public long getReconnectionDelay() {
        return reconnectionDelay;
    }

    public void setReconnectionDelay(long reconnectionDelay) {
        this.reconnectionDelay = reconnectionDelay;
    }

    public long getPingTimeout() {
        return pingTimeout;
    }

    public void setPingTimeout(long pingTimeout) {
        this.pingTimeout = pingTimeout;
    }

    public long getPingInterval() {
        return pingInterval;
    }

    public void setPingInterval(long pingInterval) {
        this.pingInterval = pingInterval;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Map<String, String> getQuery() {
        return query;
    }

    public void setQuery(Map<String, String> query) {
        this.query = query;
    }
}
