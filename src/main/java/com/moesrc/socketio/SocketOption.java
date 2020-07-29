package com.moesrc.socketio;

import io.netty.handler.ssl.SslContext;

import java.util.LinkedHashMap;
import java.util.Map;

public class SocketOption {
    private Boolean reconnection = true;
    private Integer reconnectionAttempts = 5;
    private Long reconnectionDelay = 100L;
    private Long reconnectionDelayMax = 10000L;
    private Double randomizationFactor = 0.5;
    private Long pingTimeout = 60000L;
    private Long pingInterval = 25000L;
    private String url;
    private String path = "/socket.io/";
    private Map<String, String> query = new LinkedHashMap<>();
    private SslContext sslContext;

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

    public Long getReconnectionDelayMax() {
        return reconnectionDelayMax;
    }

    public void setReconnectionDelayMax(Long reconnectionDelayMax) {
        this.reconnectionDelayMax = reconnectionDelayMax;
    }

    public Double getRandomizationFactor() {
        return randomizationFactor;
    }

    public void setRandomizationFactor(Double randomizationFactor) {
        this.randomizationFactor = randomizationFactor;
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

    public SslContext getSslContext() {
        return sslContext;
    }

    public void setSslContext(SslContext sslContext) {
        this.sslContext = sslContext;
    }
}
