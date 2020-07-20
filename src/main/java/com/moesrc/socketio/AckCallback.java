package com.moesrc.socketio;

public abstract class AckCallback {

    protected final int timeout;

    /**
     * Create AckCallback
     */
    public AckCallback() {
        this(-1);
    }

    /**
     * Creates AckCallback with timeout
     *
     * @param timeout - callback timeout in seconds
     */
    public AckCallback(int timeout) {
        this.timeout = timeout;
    }

    public int getTimeout() {
        return timeout;
    }

    /**
     * Executes only once when acknowledgement received from client.
     *
     * @param result - object sended by client
     */
    public abstract void onSuccess(Object... result);

    /**
     * Invoked only once then <code>timeout</code> defined
     */
    public void onTimeout() {

    }

}
