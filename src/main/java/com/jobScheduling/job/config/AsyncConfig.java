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

@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Value("${async.executor.core-pool-size:20}")
    private int corePoolSize;

    @Value("${async.executor.max-pool-size:200}")
    private int maxPoolSize;

    @Value("${async.executor.queue-capacity:10000}")
    private int queueCapacity;

    @Value("${async.executor.thread-name-prefix:job-async-}")
    private String threadNamePrefix;

    /**
     * Primary async executor — used for @Async annotated methods.
     * Tuned for high throughput: large queue to absorb bursts,
     * generous max threads for I/O-bound tasks.
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        // CallerRunsPolicy: if queue full, the caller thread executes — no drops
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated executor for Kafka consumer processing — isolated
     * from the main async pool to prevent consumer starvation.
     */
    @Bean(name = "kafkaExecutor")
    public Executor kafkaExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(50);
        executor.setMaxPoolSize(100);
        executor.setQueueCapacity(5000);
        executor.setThreadNamePrefix("kafka-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Scheduler for polling jobs ready to fire — virtual threads friendly.
     */
    @Bean(name = "jobSchedulerExecutor")
    public ScheduledExecutorService jobSchedulerExecutor() {
        return Executors.newScheduledThreadPool(10,
                Thread.ofVirtual().name("job-scheduler-", 0).factory());
    }
}
