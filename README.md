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

    option.setUrl("ws://127.0.0.1:8080");
    SocketIOClient socket = manager.create(option);

    socket.on("dong", new Emitter.Listener() {
        @Override
        public void call(Emitter.Ack ack, Object... args) {
            ack.send("hello dong");
        }
    });

    socket.connect();

    socket.emit("ding", new AckCallback<Object[]>() {
        @Override
        public void onSuccess(Object[] result) {
            System.out.println("received ack " + String.valueOf(result[0]));
        }
    });

    socket.disconnect();
    Manager.destroy();

```