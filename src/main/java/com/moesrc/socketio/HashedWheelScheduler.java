package com.moesrc.socketio;

import java.util.Map;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import io.netty.util.internal.PlatformDependent;

public class HashedWheelScheduler {

    private final Map<String, Timeout> scheduledFutures = PlatformDependent.newConcurrentHashMap();
    private final HashedWheelTimer executorService;

    public HashedWheelScheduler() {
        executorService = new HashedWheelTimer();
    }

    public HashedWheelScheduler(ThreadFactory threadFactory) {
        executorService = new HashedWheelTimer(threadFactory);
    }

    public void cancel(String key) {
        Timeout timeout = scheduledFutures.remove(key);
        if (timeout != null) {
            timeout.cancel();
        }
    }

    public void schedule(final Runnable runnable, long delay, TimeUnit unit) {
        executorService.newTimeout(new TimerTask() {
            @Override
            public void run(Timeout timeout) throws Exception {
                runnable.run();
            }
        }, delay, unit);
    }

    public void scheduleCallback(final ChannelHandlerContext ctx, final String key, final Runnable runnable, long delay, TimeUnit unit) {
        Timeout timeout = executorService.newTimeout(new TimerTask() {
            @Override
            public void run(Timeout timeout) throws Exception {
                ctx.executor().execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            runnable.run();
                        } finally {
                            scheduledFutures.remove(key);
                        }
                    }
                });
            }
        }, delay, unit);

        if (!timeout.isExpired()) {
            Timeout put = scheduledFutures.put(key, timeout);
            if (put != null) {
                put.cancel();
            }
        }
    }

    public void schedule(final String key, final Runnable runnable, long delay, TimeUnit unit) {
        Timeout timeout = executorService.newTimeout(new TimerTask() {
            @Override
            public void run(Timeout timeout) throws Exception {
                try {
                    runnable.run();
                } finally {
                    scheduledFutures.remove(key);
                }
            }
        }, delay, unit);

        if (!timeout.isExpired()) {
            Timeout put = scheduledFutures.put(key, timeout);
            if (put != null) {
                put.cancel();
            }
        }
    }

    public void shutdown() {
        executorService.stop();
    }

}
