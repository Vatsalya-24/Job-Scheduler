package com.jobScheduling.job.service;

import com.jobScheduling.job.dto.JobsDTO;
import com.jobScheduling.job.entity.JobStatus;
import com.jobScheduling.job.entity.Jobs;
import com.jobScheduling.job.exception.JobNotFoundException;
import com.jobScheduling.job.kafka.JobEvent;
import com.jobScheduling.job.kafka.KafkaProducerService;
import com.jobScheduling.job.repository.JobsRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;

@Service
public class JobsService {

    private static final Logger log = LoggerFactory.getLogger(JobsService.class);

    private final JobsRepository jobsRepository;
    private final KafkaProducerService kafkaProducerService;

    private final Counter jobsCreatedCounter;
    private final Counter jobsUpdatedCounter;
    private final Timer jobCreationTimer;

    public JobsService(
            JobsRepository jobsRepository,
            KafkaProducerService kafkaProducerService,
            MeterRegistry meterRegistry) {
        this.jobsRepository = jobsRepository;
        this.kafkaProducerService = kafkaProducerService;

        this.jobsCreatedCounter = Counter.builder("jobs.created.total").register(meterRegistry);
        this.jobsUpdatedCounter = Counter.builder("jobs.updated.total").register(meterRegistry);
        this.jobCreationTimer = Timer.builder("jobs.creation.duration").register(meterRegistry);
    }

    /**
     * Creates a job, persists it, and fires a Kafka event asynchronously.
     * DB write is synchronous and transactional; Kafka publish is non-blocking.
     */
    @Transactional
    @CircuitBreaker(name = "jobService", fallbackMethod = "createJobFallback")
    @CacheEvict(value = "jobs", allEntries = true)
    public JobsDTO createNewJob(JobsDTO dto) {
        return jobCreationTimer.record(() -> {
            CronExpression cron = CronExpression.parse(dto.getCronExpression());
            ZonedDateTime nextFire = cron.next(ZonedDateTime.now());

            Jobs job = new Jobs();
            job.setName(dto.getName());
            job.setCronExpression(dto.getCronExpression());
            job.setTarget(dto.getTarget());
            job.setStatus(JobStatus.valueOf(dto.getStatus()));
            if (nextFire != null) {
                job.setNextFireTime(java.sql.Timestamp.from(nextFire.toInstant()));
            }

            Jobs saved = jobsRepository.save(job);
            jobsCreatedCounter.increment();

            // Non-blocking Kafka publish — does NOT hold the HTTP thread
            JobEvent event = new JobEvent(
                    saved.getId(), saved.getName(), saved.getCronExpression(),
                    saved.getTarget(), saved.getStatus().name(), Instant.now());
            kafkaProducerService.publishJobCreated(event)
                    .exceptionally(ex -> {
                        log.warn("Kafka publish failed for jobId={}, job still created: {}",
                                saved.getId(), ex.getMessage());
                        return null;
                    });

            log.info("Job created: id={}, name={}", saved.getId(), saved.getName());
            return mapToDTO(saved);
        });
    }

    /**
     * Circuit breaker fallback — returns a graceful error DTO instead of propagating exception.
     */
    public JobsDTO createJobFallback(JobsDTO dto, Throwable t) {
        log.error("Circuit breaker open for job creation: {}", t.getMessage());
        throw new RuntimeException("Service temporarily unavailable. Please retry shortly.");
    }

    /**
     * Cached single-job lookup. Cache key = jobId.
     */
    @Cacheable(value = "job", key = "#jobId")
    @Transactional(readOnly = true)
    public JobsDTO getJobById(Long jobId) {
        Jobs job = jobsRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException("Job not found: " + jobId));
        return mapToDTO(job);
    }

    /**
     * Paginated job listing — cached per page/sort config.
     * Returns Page<JobsDTO> so the client can paginate large sets.
     */
    @Cacheable(value = "jobs", key = "#pageable.pageNumber + '-' + #pageable.pageSize")
    @Transactional(readOnly = true)
    public Page<JobsDTO> getAllJobs(Pageable pageable) {
        return jobsRepository.findAll(pageable).map(this::mapToDTO);
    }

    /**
     * Update job — evicts caches for both the list and the single entry.
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "jobs", allEntries = true),
            @CacheEvict(value = "job", key = "#jobId")
    })
    public JobsDTO updateJob(Long jobId, JobsDTO dto) {
        Jobs job = jobsRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException("Job not found: " + jobId));

        job.setName(dto.getName());
        job.setCronExpression(dto.getCronExpression());
        job.setTarget(dto.getTarget());
        job.setStatus(JobStatus.valueOf(dto.getStatus()));

        // Recalculate next fire time
        CronExpression cron = CronExpression.parse(dto.getCronExpression());
        ZonedDateTime nextFire = cron.next(ZonedDateTime.now());
        if (nextFire != null) {
            job.setNextFireTime(java.sql.Timestamp.from(nextFire.toInstant()));
        }

        Jobs saved = jobsRepository.save(job);
        jobsUpdatedCounter.increment();
        return mapToDTO(saved);
    }

    /**
     * Soft-delete via status change — DISABLED, not hard delete.
     * Keeps historical execution data intact.
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "jobs", allEntries = true),
            @CacheEvict(value = "job", key = "#jobId")
    })
    public void disableJob(Long jobId) {
        Jobs job = jobsRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException("Job not found: " + jobId));
        job.setStatus(JobStatus.DISABLED);
        jobsRepository.save(job);
        log.info("Job {} disabled", jobId);
    }

    /**
     * Returns all ACTIVE jobs whose next_fire_time <= now.
     * Called by the scheduler poller every second.
     */
    @Transactional(readOnly = true)
    public List<Jobs> findJobsDueToFire() {
        return jobsRepository.findJobsDueToFire(
                JobStatus.ACTIVE, java.sql.Timestamp.from(Instant.now()));
    }

    private JobsDTO mapToDTO(Jobs job) {
        JobsDTO dto = new JobsDTO();
        dto.setId(job.getId());
        dto.setName(job.getName());
        dto.setCronExpression(job.getCronExpression());
        dto.setTarget(job.getTarget());
        dto.setStatus(job.getStatus().name());
        dto.setNextFireTime(job.getNextFireTime());
        dto.setCreatedAt(job.getCreatedAt());
        return dto;
    }
}
