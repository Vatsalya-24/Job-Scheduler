package com.jobScheduling.job.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
public class KafkaProducerService {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducerService.class);

    private final KafkaTemplate<String, JobEvent> jobEventKafkaTemplate;
    private final KafkaTemplate<String, JobExecutionEvent> jobExecutionKafkaTemplate;

    private final Counter jobEventsSent;
    private final Counter jobEventsFailed;
    private final Counter executionEventsSent;

    @Value("${kafka.topics.job-created}")
    private String jobCreatedTopic;

    @Value("${kafka.topics.job-execution-result}")
    private String jobExecutionResultTopic;

    @Value("${kafka.topics.job-retry}")
    private String jobRetryTopic;

    public KafkaProducerService(
            KafkaTemplate<String, JobEvent> jobEventKafkaTemplate,
            KafkaTemplate<String, JobExecutionEvent> jobExecutionKafkaTemplate,
            MeterRegistry meterRegistry) {
        this.jobEventKafkaTemplate = jobEventKafkaTemplate;
        this.jobExecutionKafkaTemplate = jobExecutionKafkaTemplate;

        // Metrics counters
        this.jobEventsSent = Counter.builder("kafka.job.events.sent")
                .description("Total job events sent to Kafka")
                .register(meterRegistry);
        this.jobEventsFailed = Counter.builder("kafka.job.events.failed")
                .description("Total job events failed to send")
                .register(meterRegistry);
        this.executionEventsSent = Counter.builder("kafka.execution.events.sent")
                .description("Total execution events sent to Kafka")
                .register(meterRegistry);
    }

    /**
     * Publishes a job-created event.
     * Key = jobId.toString() — ensures all events for the same job
     * land on the same partition (ordering guarantee).
     */
    public CompletableFuture<SendResult<String, JobEvent>> publishJobCreated(JobEvent event) {
        String key = event.getJobId().toString();
        return jobEventKafkaTemplate
                .send(jobCreatedTopic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        jobEventsFailed.increment();
                        log.error("Failed to publish JobEvent for jobId={}: {}", event.getJobId(), ex.getMessage());
                    } else {
                        jobEventsSent.increment();
                        log.debug("Published JobEvent jobId={} to partition={}",
                                event.getJobId(),
                                result.getRecordMetadata().partition());
                    }
                });
    }

    /**
     * Publishes an execution result event (async, non-blocking).
     */
    public CompletableFuture<SendResult<String, JobExecutionEvent>> publishExecutionResult(
            JobExecutionEvent event) {
        String key = event.getJobId().toString();
        return jobExecutionKafkaTemplate
                .send(jobExecutionResultTopic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish ExecutionEvent for jobId={}: {}", event.getJobId(), ex.getMessage());
                    } else {
                        executionEventsSent.increment();
                    }
                });
    }

    /**
     * Publishes an execution retry event.
     */
    public CompletableFuture<SendResult<String, JobExecutionEvent>> publishRetry(
            JobExecutionEvent event) {
        String key = event.getJobId().toString();
        return jobExecutionKafkaTemplate
                .send(jobRetryTopic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish RetryEvent for jobId={}", event.getJobId(), ex);
                    } else {
                        log.info("Retry event published for jobId={} attempt={}",
                                event.getJobId(), event.getAttemptNumber());
                    }
                });
    }
}
