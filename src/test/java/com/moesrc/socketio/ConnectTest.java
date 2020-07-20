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

        socket.on("dong", new Emitter.Listener() {
            @Override
            public void call(Emitter.Ack ack, Object... args) {
                ack.send("hello dong");
            }
        });

        socket.connect();

        Thread.sleep(2000);

        socket.emit("ding"
                , new AckCallback<Object[]>() {
                    @Override
                    public void onSuccess(Object[] result) {
                        System.out.println("received ack " + String.valueOf(result[0]));
                    }
                }
                , "hello ding");

        socket.emit("ding_binary"
                , new AckCallback<Object[]>() {
                    @Override
                    public void onSuccess(Object[] result) {
                        System.out.println("received ack " + String.valueOf(result[0]));
                    }
                }
                , "hello ding binary 1".getBytes(CharsetUtil.UTF_8)
                , "hello ding binary 2".getBytes(CharsetUtil.UTF_8));

        Thread.sleep(30000);
        socket.disconnect();
        Manager.destroy();
    }
}
