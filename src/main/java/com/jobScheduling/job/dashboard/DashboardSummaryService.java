package com.jobScheduling.job.dashboard;

import com.jobScheduling.job.entity.ExecutionStatus;
import com.jobScheduling.job.entity.JobStatus;
import com.jobScheduling.job.repository.JobExecutionRepository;
import com.jobScheduling.job.repository.JobsRepository;
import com.jobScheduling.job.webhook.WebhookDeliveryRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Caching service layer for dashboard metrics.
 *
 * WHY THIS EXISTS (the @Cacheable-on-ResponseEntity bug explained):
 *   The previous DashboardMetricsController had @Cacheable directly on methods
 *   that returned ResponseEntity<Map<...>>. Spring's @Cacheable stores the exact
 *   return value in Redis. ResponseEntity is a Spring HTTP wrapper — it has no
 *   no-arg constructor, so Jackson cannot deserialise it on a cache READ.
 *   Result: first request cached fine, every subsequent request threw:
 *     "Cannot construct instance of ResponseEntity (no Creators...)"
 *
 * THE RULE: @Cacheable must only be placed on methods returning plain serialisable
 *   objects — Map, DTO, List, primitives. Never on ResponseEntity, Page<T>, or
 *   any Spring MVC / Spring Data wrapper type.
 *
 * THE FIX: This service caches plain Map<String,Object>. The controller calls
 *   this service and wraps the Map in ResponseEntity AFTER retrieval — the cache
 *   never sees the ResponseEntity.
 */
@Service
@Transactional(readOnly = true)
public class DashboardSummaryService {

    private final JobsRepository            jobsRepository;
    private final JobExecutionRepository    executionRepository;
    private final WebhookDeliveryRepository deliveryRepository;

    public DashboardSummaryService(
            JobsRepository jobsRepository,
            JobExecutionRepository executionRepository,
            WebhookDeliveryRepository deliveryRepository) {
        this.jobsRepository      = jobsRepository;
        this.executionRepository = executionRepository;
        this.deliveryRepository  = deliveryRepository;
    }

    /**
     * Cached platform-wide summary — plain Map, safe for Redis serialisation.
     * TTL 10 s (registered in RedisConfig). At 100 concurrent dashboard readers,
     * DB is hit at most once per 10 seconds instead of 100 × 8 queries/s = 800/s.
     */
    @Cacheable(value = "dashboardSummary", key = "'global'")
    public Map<String, Object> getSummary() {
        long totalJobs    = jobsRepository.count();
        long activeJobs   = jobsRepository.countByStatus(JobStatus.ACTIVE);
        long pausedJobs   = jobsRepository.countByStatus(JobStatus.PAUSED);
        long disabledJobs = jobsRepository.countByStatus(JobStatus.DISABLED);

        long totalExec   = executionRepository.count();
        long successExec = executionRepository.countByStatus(ExecutionStatus.SUCCESS);
        long failedExec  = executionRepository.countByStatus(ExecutionStatus.FAILED);
        long runningExec = executionRepository.countByStatus(ExecutionStatus.STARTED);
        double rate      = totalExec > 0 ? (double) successExec / totalExec * 100 : 0;

        long totalWh     = deliveryRepository.count();
        long deliveredWh = deliveryRepository.countByStatus("DELIVERED");
        long exhaustedWh = deliveryRepository.countByStatus("EXHAUSTED");

        return Map.of(
                "jobs", Map.of(
                        "total",    totalJobs,
                        "active",   activeJobs,
                        "paused",   pausedJobs,
                        "disabled", disabledJobs),
                "executions", Map.of(
                        "total",       totalExec,
                        "success",     successExec,
                        "failed",      failedExec,
                        "running",     runningExec,
                        "successRate", Math.round(rate * 10.0) / 10.0),
                "webhooks", Map.of(
                        "total",     totalWh,
                        "delivered", deliveredWh,
                        "exhausted", exhaustedWh));
    }

    /** Per-job stats — cached 10 s, evicted on job updates. */
    @Cacheable(value = "jobStats", key = "#jobId")
    public Map<String, Object> getJobStats(Long jobId) {
        long total   = executionRepository.countByJobsId(jobId);
        long success = executionRepository.countByJobsIdAndStatus(jobId, ExecutionStatus.SUCCESS);
        long failed  = executionRepository.countByJobsIdAndStatus(jobId, ExecutionStatus.FAILED);
        long running = executionRepository.countByJobsIdAndStatus(jobId, ExecutionStatus.STARTED);
        double rate  = total > 0 ? (double) success / total * 100 : 0;

        return Map.of(
                "jobId",       jobId,
                "total",       total,
                "success",     success,
                "failed",      failed,
                "running",     running,
                "successRate", Math.round(rate * 10.0) / 10.0);
    }
}
