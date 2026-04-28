package com.jobScheduling.job.scheduler;

import com.jobScheduling.job.entity.ExecutionStatus;
import com.jobScheduling.job.entity.JobExecution;
import com.jobScheduling.job.entity.JobStatus;
import com.jobScheduling.job.entity.Jobs;
import com.jobScheduling.job.kafka.JobExecutionEvent;
import com.jobScheduling.job.kafka.KafkaProducerService;
import com.jobScheduling.job.repository.JobExecutionRepository;
import com.jobScheduling.job.repository.JobsRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Polls for due jobs and dispatches execution events to Kafka.
 *
 * Uses {@link DistributedSchedulerLock} to ensure only ONE instance
 * fires jobs in a given poll cycle — safe for horizontal scaling.
 */
@Component
public class JobSchedulerPoller {

    private static final Logger log = LoggerFactory.getLogger(JobSchedulerPoller.class);

    private final JobsRepository jobsRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final KafkaProducerService kafkaProducerService;
    private final DistributedSchedulerLock distributedLock;

    private final AtomicInteger dueJobsGauge = new AtomicInteger(0);
    private final Counter jobsFiredCounter;
    private final Counter pollSkippedCounter;
    private final Timer pollTimer;

    public JobSchedulerPoller(
            JobsRepository jobsRepository,
            JobExecutionRepository jobExecutionRepository,
            KafkaProducerService kafkaProducerService,
            DistributedSchedulerLock distributedLock,
            MeterRegistry meterRegistry) {
        this.jobsRepository = jobsRepository;
        this.jobExecutionRepository = jobExecutionRepository;
        this.kafkaProducerService = kafkaProducerService;
        this.distributedLock = distributedLock;

        this.jobsFiredCounter = Counter.builder("scheduler.jobs.fired")
                .description("Total jobs dispatched for execution")
                .register(meterRegistry);
        this.pollSkippedCounter = Counter.builder("scheduler.poll.skipped")
                .description("Poll cycles skipped (lock not acquired)")
                .register(meterRegistry);
        this.pollTimer = Timer.builder("scheduler.poll.duration")
                .description("Time taken per scheduler poll cycle")
                .register(meterRegistry);

        Gauge.builder("scheduler.due_jobs", dueJobsGauge, AtomicInteger::get)
                .description("Number of jobs fired in last poll")
                .register(meterRegistry);
    }

    /**
     * Core polling loop — runs every 1 second.
     * Fixed delay ensures runs don't overlap even if processing takes longer.
     */
    @Scheduled(fixedDelay = 1000)
    public void pollAndDispatch() {
        if (!distributedLock.tryAcquire()) {
            pollSkippedCounter.increment();
            return;
        }
        try {
            pollTimer.record(this::doDispatch);
        } finally {
            distributedLock.release();
        }
    }

    @Transactional
    protected void doDispatch() {
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        List<Jobs> dueJobs = jobsRepository.findJobsDueToFire(JobStatus.ACTIVE, nowTs);

        if (dueJobs.isEmpty()) {
            dueJobsGauge.set(0);
            return;
        }

        dueJobsGauge.set(dueJobs.size());
        log.info("Scheduler firing {} jobs at {}", dueJobs.size(), now);

        List<JobExecution> executionsToSave = new ArrayList<>(dueJobs.size());
        List<Jobs> jobsToUpdate = new ArrayList<>(dueJobs.size());

        for (Jobs job : dueJobs) {
            JobExecution execution = new JobExecution(
                    ExecutionStatus.STARTED, Timestamp.from(now), null, null, job);
            executionsToSave.add(execution);
            advanceNextFireTime(job);
            jobsToUpdate.add(job);
        }

        List<JobExecution> saved = jobExecutionRepository.saveAll(executionsToSave);
        jobsRepository.saveAll(jobsToUpdate);

        for (int i = 0; i < saved.size(); i++) {
            JobExecution exec = saved.get(i);
            Jobs job = dueJobs.get(i);
            JobExecutionEvent event = new JobExecutionEvent(
                    job.getId(), (long) exec.getId(),
                    ExecutionStatus.STARTED.name(), now, null, null, 1);
            kafkaProducerService.publishExecutionResult(event)
                    .exceptionally(ex -> {
                        log.error("Kafka publish failed for jobId={}: {}", job.getId(), ex.getMessage());
                        return null;
                    });
        }

        jobsFiredCounter.increment(saved.size());
        log.info("Dispatched {} execution events to Kafka", saved.size());
    }

    private void advanceNextFireTime(Jobs job) {
        try {
            CronExpression cron = CronExpression.parse(job.getCronExpression());
            ZonedDateTime next = cron.next(ZonedDateTime.now());
            if (next != null) {
                job.setNextFireTime(Timestamp.from(next.toInstant()));
            } else {
                job.setStatus(JobStatus.DISABLED);
                log.info("Job {} has no future occurrences — disabling", job.getId());
            }
        } catch (Exception e) {
            log.error("Failed to compute next fire time for jobId={}: {}", job.getId(), e.getMessage());
        }
    }
}
