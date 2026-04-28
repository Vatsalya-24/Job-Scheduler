package com.jobScheduling.job.service;

import com.jobScheduling.job.dto.JobsDTO;
import com.jobScheduling.job.entity.JobStatus;
import com.jobScheduling.job.entity.Jobs;
import com.jobScheduling.job.exception.JobNotFoundException;
import com.jobScheduling.job.repository.JobsRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.ZonedDateTime;

/**
 * Manages job lifecycle transitions beyond basic CRUD:
 *   ACTIVE → PAUSED  : job stops firing but retains its cron schedule
 *   PAUSED → ACTIVE  : job resumes — next fire time recalculated from NOW
 *   ANY    → DISABLED: soft delete — job never fires again, history preserved
 *
 * Resume talking point:
 *   "Implemented safe job lifecycle management with atomic status transitions
 *    and automatic next-fire-time recalculation on resume."
 */
@Service
public class JobLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(JobLifecycleService.class);

    private final JobsRepository jobsRepository;
    private final Counter pausedCounter;
    private final Counter resumedCounter;

    public JobLifecycleService(JobsRepository jobsRepository, MeterRegistry meterRegistry) {
        this.jobsRepository = jobsRepository;
        this.pausedCounter  = Counter.builder("jobs.paused.total").register(meterRegistry);
        this.resumedCounter = Counter.builder("jobs.resumed.total").register(meterRegistry);
    }

    /**
     * Pause an ACTIVE job.
     * The next_fire_time is preserved so we know where to resume from,
     * but the scheduler poller only fires ACTIVE jobs — so it won't fire.
     */
    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "job",  key = "#jobId"),
        @CacheEvict(value = "jobs", allEntries = true)
    })
    public JobsDTO pause(Long jobId) {
        Jobs job = findActive(jobId);
        if (job.getStatus() == JobStatus.PAUSED) {
            log.info("Job {} is already paused", jobId);
            return mapToDTO(job);
        }
        job.setStatus(JobStatus.PAUSED);
        Jobs saved = jobsRepository.save(job);
        pausedCounter.increment();
        log.info("Job {} paused", jobId);
        return mapToDTO(saved);
    }

    /**
     * Resume a PAUSED job.
     * Recalculates next_fire_time from NOW so the job picks up on its
     * natural schedule rather than trying to catch up on missed fires.
     */
    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "job",  key = "#jobId"),
        @CacheEvict(value = "jobs", allEntries = true)
    })
    public JobsDTO resume(Long jobId) {
        Jobs job = jobsRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException("Job not found: " + jobId));

        if (job.getStatus() == JobStatus.DISABLED) {
            throw new IllegalStateException("Cannot resume a DISABLED job. Create a new job instead.");
        }
        if (job.getStatus() == JobStatus.ACTIVE) {
            log.info("Job {} is already active", jobId);
            return mapToDTO(job);
        }

        // Recalculate next fire from NOW — skip any missed windows
        try {
            CronExpression cron = CronExpression.parse(job.getCronExpression());
            ZonedDateTime next = cron.next(ZonedDateTime.now());
            if (next != null) {
                job.setNextFireTime(Timestamp.from(next.toInstant()));
            }
        } catch (Exception e) {
            log.warn("Could not recalculate next fire for job {}: {}", jobId, e.getMessage());
        }

        job.setStatus(JobStatus.ACTIVE);
        Jobs saved = jobsRepository.save(job);
        resumedCounter.increment();
        log.info("Job {} resumed, next fire: {}", jobId, saved.getNextFireTime());
        return mapToDTO(saved);
    }

    /**
     * Trigger an immediate one-off execution outside the normal schedule.
     * Sets next_fire_time to NOW so the scheduler picks it up in the next poll cycle.
     * After firing, the scheduler advances next_fire_time normally.
     */
    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "job",  key = "#jobId"),
        @CacheEvict(value = "jobs", allEntries = true)
    })
    public JobsDTO triggerNow(Long jobId) {
        Jobs job = findActive(jobId);
        job.setNextFireTime(Timestamp.from(java.time.Instant.now()));
        Jobs saved = jobsRepository.save(job);
        log.info("Job {} triggered for immediate execution", jobId);
        return mapToDTO(saved);
    }

    private Jobs findActive(Long jobId) {
        Jobs job = jobsRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException("Job not found: " + jobId));
        if (job.getStatus() == JobStatus.DISABLED) {
            throw new IllegalStateException("Job " + jobId + " is DISABLED.");
        }
        return job;
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
