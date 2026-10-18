package com.gossiplog.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async executors. Two separate pools so slow FCM calls can never starve or reorder
 * room broadcasts.
 *
 * <p>Neither pool drops work: when the queue is full the submitting thread runs the
 * task itself ({@link ThreadPoolExecutor.CallerRunsPolicy}) — back-pressure instead of
 * silently losing a broadcast or a push.
 */
@Configuration
public class AsyncConfig {

    /** STOMP room broadcasts after commit ({@code MessageEventListener}). */
    public static final String BROADCAST_EXECUTOR = "broadcastExecutor";

    /** Firebase sends and topic (un)subscriptions ({@code FcmNotificationService}). */
    public static final String FCM_EXECUTOR = "fcmExecutor";

    static final int BROADCAST_QUEUE_CAPACITY = 1000;
    static final int FCM_QUEUE_CAPACITY = 500;
    private static final int SHUTDOWN_DRAIN_SECONDS = 10;

    /**
     * A single worker drains broadcasts in submission (= commit) order, so two
     * messages in the same room reach subscribers in the order they were persisted.
     * A multi-thread pool would let a later message overtake an earlier one.
     */
    @Bean(BROADCAST_EXECUTOR)
    public ThreadPoolTaskExecutor broadcastExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(BROADCAST_QUEUE_CAPACITY);
        executor.setThreadNamePrefix("broadcast-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        drainOnShutdown(executor);
        executor.initialize();
        return executor;
    }

    @Bean(FCM_EXECUTOR)
    public ThreadPoolTaskExecutor fcmExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(FCM_QUEUE_CAPACITY);
        executor.setThreadNamePrefix("fcm-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        drainOnShutdown(executor);
        executor.initialize();
        return executor;
    }

    /** Graceful shutdown runs what is already queued instead of discarding it. */
    private static void drainOnShutdown(ThreadPoolTaskExecutor executor) {
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_DRAIN_SECONDS);
    }
}
