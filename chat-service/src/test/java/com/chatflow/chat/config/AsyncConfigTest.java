package com.chatflow.chat.config;

import com.chatflow.chat.event.MessageEventListener;
import com.chatflow.chat.event.MessagePersistedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncConfigTest {

    private final AsyncConfig config = new AsyncConfig();
    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) executor.shutdown();
    }

    @Test
    void broadcast_queueSaturated_callerRuns_notDropped() throws Exception {
        executor = config.broadcastExecutor();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workerBusy = new CountDownLatch(1);

        // Pin the single worker thread.
        executor.execute(() -> {
            workerBusy.countDown();
            awaitQuietly(release);
        });
        assertThat(workerBusy.await(5, TimeUnit.SECONDS)).isTrue();

        // Fill the queue completely.
        List<Integer> ran = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < AsyncConfig.BROADCAST_QUEUE_CAPACITY; i++) {
            int n = i;
            executor.execute(() -> ran.add(n));
        }

        // One more: must run on the calling thread instead of being dropped.
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        executor.execute(() -> ranOn.set(Thread.currentThread()));
        assertThat(ranOn.get()).isSameAs(Thread.currentThread());

        release.countDown();
        executor.shutdown(); // drains the queue (waitForTasksToCompleteOnShutdown)
        assertThat(ran).hasSize(AsyncConfig.BROADCAST_QUEUE_CAPACITY);
    }

    @Test
    void broadcastExecutor_isSingleThreaded_soRoomOrderIsPreserved() throws Exception {
        executor = config.broadcastExecutor();
        assertThat(executor.getCorePoolSize()).isEqualTo(1);
        assertThat(executor.getMaxPoolSize()).isEqualTo(1);

        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 200; i++) {
            int n = i;
            executor.execute(() -> order.add(n));
        }
        executor.shutdown();
        assertThat(order).hasSize(200);
        for (int i = 0; i < order.size(); i++) {
            assertThat(order.get(i)).isEqualTo(i);
        }
    }

    @Test
    void fcmExecutor_queueSaturated_callerRuns_notDropped() throws Exception {
        executor = config.fcmExecutor();
        assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy.class);
    }

    @Test
    void messageBroadcast_usesDedicatedBroadcastExecutor() throws Exception {
        Async async = MessageEventListener.class
                .getMethod("onMessagePersisted", MessagePersistedEvent.class)
                .getAnnotation(Async.class);
        assertThat(async.value()).isEqualTo(AsyncConfig.BROADCAST_EXECUTOR);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
