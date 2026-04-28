package com.jobScheduling.job.service;

import com.jobScheduling.job.dto.JobExecutionDTO;
import com.jobScheduling.job.entity.ExecutionStatus;
import com.jobScheduling.job.entity.JobExecution;
import com.jobScheduling.job.entity.Jobs;
import com.jobScheduling.job.exception.JobNotFoundException;
import com.jobScheduling.job.kafka.JobExecutionEvent;
import com.jobScheduling.job.kafka.KafkaProducerService;
import com.jobScheduling.job.repository.JobExecutionRepository;
import com.jobScheduling.job.repository.JobsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * FIX SUMMARY — what was wrong and why:
 *
 * ROOT CAUSE 1 — existsById() hits DB on EVERY execution record request.
 *   The load test fires 2000 executions/s. Each one called jobsRepository.existsById()
 *   which issues SELECT EXISTS(SELECT 1 FROM jobs WHERE id=?) — 2000 DB round-trips/s
 *   just for validation. That saturated HikariCP (100 conns), causing all queries to
 *   queue → connection-timeout (3s) → 503s. Fix: cache the existence check in Redis
 *   with a 10-minute TTL so the DB is hit once, not on every request.
 *
 * ROOT CAUSE 2 — CompletableFuture propagation swallowed exceptions silently.
 *   When existsById DID throw (pool exhausted), the exception propagated into the
 *   CompletableFuture chain and the controller's .thenApply() never ran — the future
 *   completed exceptionally but the controller returned an empty 200 instead of 500,
 *   which confused k6 checks (it expected 202 but got 200 with empty body).
 *   Fix: validate BEFORE touching the CompletableFuture chain.
 *
 * ROOT CAUSE 3 — Kafka producer acks=all + linger=5ms blocked under load.
 *   With 2000 req/s and acks=all, every publish waited for all ISR replicas. With a
 *   single-broker dev setup this means broker FSM delays. linger=5ms means each batch
 *   waits 5ms before sending. Fix: for the execution path use acks=1 (leader only) —
 *   we can tolerate rare loss of an execution record; we cannot tolerate 5s latency.
 *   Job creation keeps acks=all for durability.
 */
@Service
public class JobExecutionService {

    private static final Logger log = LoggerFactory.getLogger(JobExecutionService.class);

    // Redis key prefix for job-existence cache
    private static final String JOB_EXISTS_PREFIX = "job:exists:";
    private static final Duration JOB_EXISTS_TTL  = Duration.ofMinutes(10);

    private final JobExecutionRepository jobExecutionRepository;
    private final JobsRepository         jobsRepository;
    private final KafkaProducerService   kafkaProducerService;
    private final StringRedisTemplate    redis;

    public JobExecutionService(
            JobExecutionRepository jobExecutionRepository,
            JobsRepository jobsRepository,
            KafkaProducerService kafkaProducerService,
            StringRedisTemplate redis) {
        this.jobExecutionRepository = jobExecutionRepository;
        this.jobsRepository         = jobsRepository;
        this.kafkaProducerService   = kafkaProducerService;
        this.redis                  = redis;
    }

    /**
     * Records an execution result asynchronously via Kafka.
     *
     * Hot path — called at 2000+ req/s.  Must not touch DB unless necessary.
     * Existence check uses Redis; Kafka publish is non-blocking.
     */
    public CompletableFuture<Void> recordExecutionAsync(JobExecutionDTO dto) {
        // ── Validate FIRST, before any async boundary ────────────────────────
        // This way exceptions surface synchronously and the controller can map
        // them to the correct HTTP status via GlobalExceptionHandler.
        validateJobExists(dto.getJobId());

        JobExecutionEvent event = new JobExecutionEvent(
                dto.getJobId(),
                dto.getId() != null ? dto.getId() : null,
                dto.getStatus(),
                dto.getStartedAt() != null ? dto.getStartedAt().toInstant() : Instant.now(),
                dto.getFinishedAt() != null ? dto.getFinishedAt().toInstant() : Instant.now(),
                dto.getErrorMessage(),
                1
        );

        return kafkaProducerService.publishExecutionResult(event)
                .thenApply(result -> {
                    log.debug("Execution event queued for jobId={}", dto.getJobId());
                    return (Void) null;
                })
                .exceptionally(ex -> {
                    // Surface Kafka failures as runtime exceptions so the controller
                    // returns 500 rather than hanging or returning an empty 200.
                    log.error("Kafka publish failed for jobId={}: {}", dto.getJobId(), ex.getMessage());
                    throw new RuntimeException("Execution queue unavailable: " + ex.getMessage(), ex);
                });
    }

    /**
     * Check job existence — Redis first, DB fallback, result cached.
     * Saves ~2000 DB queries/s under full load.
     */
    private void validateJobExists(Long jobId) {
        String redisKey = JOB_EXISTS_PREFIX + jobId;
        try {
            String cached = redis.opsForValue().get(redisKey);
            if ("1".equals(cached)) return;          // cache hit — job exists
            if ("0".equals(cached)) {                // cache hit — job does not exist
                throw new JobNotFoundException("Job not found: " + jobId);
            }
        } catch (JobNotFoundException e) {
            throw e;
        } catch (Exception redisEx) {
            // Redis unavailable — fall through to DB (graceful degradation)
            log.warn("Redis unavailable for existence check, falling back to DB: {}", redisEx.getMessage());
        }

        // Cache miss — query DB and populate cache
        boolean exists = jobsRepository.existsById(jobId);
        try {
            redis.opsForValue().set(redisKey, exists ? "1" : "0", JOB_EXISTS_TTL);
        } catch (Exception e) {
            log.warn("Failed to cache job existence for jobId={}: {}", jobId, e.getMessage());
        }

        if (!exists) throw new JobNotFoundException("Job not found: " + jobId);
    }

    /**
     * Invalidate the existence cache when a job is disabled/deleted.
     * Called by JobsService.disableJob().
     */
    public void evictJobExistsCache(Long jobId) {
        try {
            redis.delete(JOB_EXISTS_PREFIX + jobId);
        } catch (Exception e) {
            log.warn("Failed to evict job exists cache for jobId={}: {}", jobId, e.getMessage());
        }
    }

    @Transactional
    public JobExecutionDTO recordExecutionSync(JobExecutionDTO dto) {
        Jobs job = jobsRepository.findById(dto.getJobId())
                .orElseThrow(() -> new JobNotFoundException("Job not found: " + dto.getJobId()));

        JobExecution execution = new JobExecution();
        execution.setStatus(ExecutionStatus.valueOf(dto.getStatus()));
        execution.setStartedAt(dto.getStartedAt());
        execution.setFinishedAt(dto.getFinishedAt());
        execution.setErrorMessage(dto.getErrorMessage());
        execution.setJobs(job);

        return mapToDTO(jobExecutionRepository.save(execution));
    }

    @Async("kafkaExecutor")
    @Transactional
    public CompletableFuture<List<JobExecution>> recordBatch(List<JobExecution> executions) {
        return CompletableFuture.completedFuture(jobExecutionRepository.saveAll(executions));
    }

    @Cacheable(value = "executions", key = "#jobId + '-' + #pageable.pageNumber")
    @Transactional(readOnly = true)
    public Page<JobExecutionDTO> getExecutionsByJob(Long jobId, Pageable pageable) {
        validateJobExists(jobId);
        return jobExecutionRepository.findByJobsId(jobId, pageable).map(this::mapToDTO);
    }

    private JobExecutionDTO mapToDTO(JobExecution e) {
        JobExecutionDTO dto = new JobExecutionDTO();
        dto.setId(e.getId());
        dto.setStatus(e.getStatus().toString());
        dto.setStartedAt(e.getStartedAt());
        dto.setFinishedAt(e.getFinishedAt());
        dto.setErrorMessage(e.getErrorMessage());
        dto.setJobId(e.getJobs().getId());
        return dto;
    }
}
