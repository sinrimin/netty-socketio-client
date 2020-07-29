package com.moesrc.socketio;

import io.netty.util.CharsetUtil;
import org.junit.Test;

import javax.net.ssl.SSLException;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;

public class ConnectTest {

    @Test
    public void test01() throws InterruptedException, SSLException, URISyntaxException {
        Manager manager = Manager.getInstance();
        SocketOption option = new SocketOption();

        Map<String, String> query = new LinkedHashMap<>();
        query.put("uid", "123123");
        query.put("token", "auth-token-abcdef");
        option.setQuery(query);

        option.setUrl("ws://127.0.0.1:2727");
        SocketIOClient socket = manager.create(option);

        // register event listener
        socket.on("dong", new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                System.out.println("received dong: " + String.valueOf(args[0]));
            }
        });

        // register event listener with ack
        socket.on("dong_ack", new Emitter.Listener() {
            @Override
            public void call(AckRequest ack, Object... args) {
                System.out.println("received dong_ack: " + String.valueOf(args[0]));
                ack.send("hello dong_ack");
            }
        });

        socket.connect();

        // wait for connect
        Thread.sleep(2000);

        // send event with callback
        socket.emit("ding"
                , "hello ding"
                , new AckCallback() {
                    @Override
                    public void onSuccess(Object... result) {
                        System.out.println("received ding ack: " + String.valueOf(result[0]));
                    }
                });

        // send binary event with callback
        socket.emit("ding_binary"
                , "hello ding binary 1".getBytes(CharsetUtil.UTF_8)
                , "hello ding binary 2".getBytes(CharsetUtil.UTF_8)
                , new AckCallback() {
                    @Override
                    public void onSuccess(Object... result) {
                        System.out.println("received ding_binary ack: " + String.valueOf(result[0]));
                    }
                });

        // send no data event
        socket.emit("no_body");

        // send no data event with callback
        socket.emit("no_body", new AckCallback() {
            @Override
            public void onSuccess(Object... result) {
                System.out.println("received no_body ack");
            }
        });

        // wait for test complete
        Thread.sleep(30000);
        socket.disconnect();
        Manager.destroy();
    }
}
