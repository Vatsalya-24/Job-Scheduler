package com.jobScheduling.job.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Async thread pool config — tuned for Render's memory constraints.
 *
 * CHANGES FOR RENDER:
 *   Render free tier: 512MB RAM
 *   Each Java thread stack = ~256KB - 1MB default
 *   Original config: 400 + 200 + 10 = 610 threads = potential OOM
 *
 *   New config: ~30 threads total + virtual threads for Tomcat
 *   Virtual threads (Java 21) handle the I/O wait without OS thread overhead.
 *   Platform threads only needed for CPU-bound work or thread-locals.
 *
 *   Configurable via env vars so you can scale up on paid Render tiers:
 *     ASYNC_CORE_POOL=5, ASYNC_MAX_POOL=20 (free)
 *     ASYNC_CORE_POOL=20, ASYNC_MAX_POOL=100 (starter)
 *     ASYNC_CORE_POOL=50, ASYNC_MAX_POOL=400 (standard)
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Value("${async.executor.core-pool-size:5}")
    private int corePoolSize;

    @Value("${async.executor.max-pool-size:20}")
    private int maxPoolSize;

    @Value("${async.executor.queue-capacity:1000}")
    private int queueCapacity;

    @Value("${async.executor.thread-name-prefix:orbit-async-}")
    private String threadNamePrefix;

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "kafkaExecutor")
    public Executor kafkaExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(2, corePoolSize / 2));
        executor.setMaxPoolSize(Math.max(5, maxPoolSize / 4));
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("kafka-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    @Bean(name = "jobSchedulerExecutor")
    public ScheduledExecutorService jobSchedulerExecutor() {
        // Virtual threads for the scheduler — near-zero memory overhead
        return Executors.newScheduledThreadPool(2,
                Thread.ofVirtual().name("job-scheduler-", 0).factory());
    }
}