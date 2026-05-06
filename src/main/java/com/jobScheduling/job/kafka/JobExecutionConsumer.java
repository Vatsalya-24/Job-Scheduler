package com.jobScheduling.job.kafka;

import com.jobScheduling.job.entity.ExecutionStatus;
import com.jobScheduling.job.entity.JobExecution;
import com.jobScheduling.job.entity.Jobs;
import com.jobScheduling.job.repository.JobExecutionRepository;
import com.jobScheduling.job.repository.JobsRepository;
import com.jobScheduling.job.webhook.WebhookService;
import com.jobScheduling.job.websocket.ExecutionEventPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Consumes job-execution-result events from Kafka.
 *
 * After every batch persist it:
 *   1. Pushes each update over WebSocket  ->  dashboard sees it live
 *   2. Dispatches webhooks               ->  developer endpoints receive callbacks
 *
 * Both side-effects are async/fire-and-forget so they never block the consumer.
 */
@Component
public class JobExecutionConsumer {

    private static final Logger log = LoggerFactory.getLogger(JobExecutionConsumer.class);
    private static final int MAX_RETRY_ATTEMPTS = 3;

    private final JobExecutionRepository jobExecutionRepository;
    private final JobsRepository jobsRepository;
    private final KafkaProducerService producerService;
    private final WebhookService webhookService;
    private final ExecutionEventPublisher wsPublisher;

    private final Counter processedCounter;
    private final Counter failedCounter;
    private final Timer batchProcessingTimer;

    public JobExecutionConsumer(
            JobExecutionRepository jobExecutionRepository,
            JobsRepository jobsRepository,
            KafkaProducerService producerService,
            WebhookService webhookService,
            ExecutionEventPublisher wsPublisher,
            MeterRegistry meterRegistry) {
        this.jobExecutionRepository = jobExecutionRepository;
        this.jobsRepository         = jobsRepository;
        this.producerService        = producerService;
        this.webhookService         = webhookService;
        this.wsPublisher            = wsPublisher;

        this.processedCounter     = Counter.builder("kafka.consumer.executions.processed").register(meterRegistry);
        this.failedCounter        = Counter.builder("kafka.consumer.executions.failed").register(meterRegistry);
        this.batchProcessingTimer = Timer.builder("kafka.consumer.batch.duration").register(meterRegistry);
    }

    @KafkaListener(
            topics = "${kafka.topics.job-execution-result}",
            groupId = "job-execution-group",
            containerFactory = "jobExecutionListenerFactory",
            concurrency = "${kafka.consumer.concurrency:10}"
    )
    public void consumeExecutionResults(List<JobExecutionEvent> events, Acknowledgment ack) {
        log.debug("Processing batch of {} execution events", events.size());

        batchProcessingTimer.record(() -> {
            try {
                List<JobExecution>      executions = new ArrayList<>(events.size());
                List<JobExecutionEvent> valid      = new ArrayList<>(events.size());

                for (JobExecutionEvent ev : events) {
                    JobExecution exec = mapToEntity(ev);
                    if (exec != null) { executions.add(exec); valid.add(ev); }
                }

                // One DB round-trip for the entire batch
                List<JobExecution> saved = jobExecutionRepository.saveAll(executions);
                processedCounter.increment(saved.size());

                // Commit BEFORE async side-effects — message is safe in DB
                ack.acknowledge();

                // Fire WebSocket push + webhook for each saved execution (async)
                for (int i = 0; i < saved.size(); i++) {
                    JobExecution    exec = saved.get(i);
                    JobExecutionEvent ev = valid.get(i);

                    wsPublisher.publishExecutionUpdate(
                            ev.getJobId(), exec.getId(),
                            ev.getStatus(), "job-" + ev.getJobId());

                    webhookService.dispatch(
                            ev.getJobId(), exec.getId(),
                            ev.getStatus(), buildPayload(ev, exec));
                }

            } catch (Exception ex) {
                failedCounter.increment(events.size());
                log.error("Batch failed ({} events): {}", events.size(), ex.getMessage(), ex);
                throw ex;
            }
        });
    }

    @KafkaListener(
            topics = "${kafka.topics.job-retry}",
            groupId = "job-retry-group",
            containerFactory = "jobExecutionListenerFactory"
    )
    public void consumeRetryEvents(List<JobExecutionEvent> events, Acknowledgment ack) {
        for (JobExecutionEvent ev : events) {
            if (ev.getAttemptNumber() >= MAX_RETRY_ATTEMPTS) {
                log.warn("Job {} exceeded max retries. Skipping.", ev.getJobId());
                continue;
            }
            log.info("Retry for jobId={} attempt={}", ev.getJobId(), ev.getAttemptNumber());
        }
        ack.acknowledge();
    }

    private JobExecution mapToEntity(JobExecutionEvent ev) {
        try {
            Jobs job = jobsRepository.findById(ev.getJobId()).orElse(null);
            if (job == null) { log.warn("Job not found: {}", ev.getJobId()); return null; }
            JobExecution exec = new JobExecution();
            exec.setStatus(ExecutionStatus.valueOf(ev.getStatus()));
            exec.setStartedAt(ev.getStartedAt() != null
                    ? Timestamp.from(ev.getStartedAt()) : Timestamp.from(Instant.now()));
            exec.setFinishedAt(ev.getFinishedAt() != null
                    ? Timestamp.from(ev.getFinishedAt()) : null);
            exec.setErrorMessage(ev.getErrorMessage());
            exec.setJobs(job);
            return exec;
        } catch (Exception e) {
            log.error("Map failed jobId={}: {}", ev.getJobId(), e.getMessage());
            return null;
        }
    }

    private Map<String, Object> buildPayload(JobExecutionEvent ev, JobExecution saved) {
        return Map.of(
                "jobId",       ev.getJobId(),
                "executionId", saved.getId(),
                "status",      ev.getStatus(),
                "startedAt",   ev.getStartedAt() != null ? ev.getStartedAt().toString() : "",
                "finishedAt",  ev.getFinishedAt() != null ? ev.getFinishedAt().toString() : "",
                "errorMessage", ev.getErrorMessage() != null ? ev.getErrorMessage() : "",
                "attempt",     ev.getAttemptNumber()
        );
    }
}
