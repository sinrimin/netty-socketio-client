# netty-socketio-client

This is a Java implementation of [Socket.IO](http://socket.io/) client. Based on [Netty](http://netty.io/) framework.  

Reference some source from:
* [socket.io-client-java](https://github.com/socketio/socket.io-client-java)
* [netty-socketio](https://github.com/mrniko/netty-socketio)

Licensed under the Apache License 2.0.

## Features
* Supports websocket transport  
* Supports ack & binary ack (acknowledgment of received data) 
* Supports event emitter
* Supports binary event
* High performance in multi connection

## Todo list
* Namespace
* API Document
* Javadoc comment

## Attention
* Will NOT support xhr-polling transport
* Don't use in production environment (Doesn't have enough test)

## Usage
```java
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
            System.out.println("received dong" + String.valueOf(args[0]));
        }
    });

    // register event listener with ack
    socket.on("dong_ack", new Emitter.Listener() {
        @Override
        public void call(AckRequest ack, Object... args) {
            System.out.println("received dong" + String.valueOf(args[0]));
            ack.send("hello dong");
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
                    System.out.println("received ding ack " + String.valueOf(result[0]));
                }
            });

    // send binary event with callback
    socket.emit("ding_binary"
            , "hello ding binary 1".getBytes(CharsetUtil.UTF_8)
            , "hello ding binary 2".getBytes(CharsetUtil.UTF_8)
            , new AckCallback() {
                @Override
                public void onSuccess(Object... result) {
                    System.out.println("received ding_binary ack " + String.valueOf(result[0]));
                }
            });

    // send no data event
    socket.emit("no_body");

    // send no data event with callback
    socket.emit("no_body", new AckCallback() {
        @Override
        public void onSuccess(Object... result) {
            System.out.println("received ding_binary ack " + String.valueOf(result[0]));
        }
    });

    // wait for test complete
    Thread.sleep(30000);
    socket.disconnect();
    Manager.destroy();

```