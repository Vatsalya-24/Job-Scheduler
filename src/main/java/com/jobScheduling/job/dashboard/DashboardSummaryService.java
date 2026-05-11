package com.jobScheduling.job.dashboard;

import com.jobScheduling.job.entity.ExecutionStatus;
import com.jobScheduling.job.entity.JobStatus;
import com.jobScheduling.job.repository.JobExecutionRepository;
import com.jobScheduling.job.repository.JobsRepository;
import com.jobScheduling.job.webhook.WebhookDeliveryRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import  java.util.HashMap;
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
 */
@Service
@Transactional(readOnly = true)
public class DashboardSummaryService {

    private final JobsRepository jobsRepository;
    private final JobExecutionRepository executionRepository;
    private final WebhookDeliveryRepository deliveryRepository;

    public DashboardSummaryService(
            JobsRepository jobsRepository,
            JobExecutionRepository executionRepository,
            WebhookDeliveryRepository deliveryRepository) {

        this.jobsRepository = jobsRepository;
        this.executionRepository = executionRepository;
        this.deliveryRepository = deliveryRepository;
    }

    /**
     * Cached platform-wide summary.
     * TTL handled in RedisConfig.
     */
    @Cacheable(value = "dashboardSummary", key = "'global'")
    public Map<String, Object> getSummary() {

        long totalJobs = jobsRepository.count();
        long activeJobs = jobsRepository.countByStatus(JobStatus.ACTIVE);
        long pausedJobs = jobsRepository.countByStatus(JobStatus.PAUSED);
        long disabledJobs = jobsRepository.countByStatus(JobStatus.DISABLED);

        long totalExec = executionRepository.count();
        long successExec = executionRepository.countByStatus(ExecutionStatus.SUCCESS);
        long failedExec = executionRepository.countByStatus(ExecutionStatus.FAILED);
        long runningExec = executionRepository.countByStatus(ExecutionStatus.STARTED);

        double rate = totalExec > 0
                ? (double) successExec / totalExec * 100
                : 0;

        long totalWh = deliveryRepository.count();
        long deliveredWh = deliveryRepository.countByStatus("DELIVERED");
        long exhaustedWh = deliveryRepository.countByStatus("EXHAUSTED");

        Map<String, Object> jobs = new HashMap<>();
        jobs.put("total", totalJobs);
        jobs.put("active", activeJobs);
        jobs.put("paused", pausedJobs);
        jobs.put("disabled", disabledJobs);

        Map<String, Object> executions = new HashMap<>();
        executions.put("total", totalExec);
        executions.put("success", successExec);
        executions.put("failed", failedExec);
        executions.put("running", runningExec);
        executions.put("successRate", Math.round(rate * 10.0) / 10.0);

        Map<String, Object> webhooks = new HashMap<>();
        webhooks.put("total", totalWh);
        webhooks.put("delivered", deliveredWh);
        webhooks.put("exhausted", exhaustedWh);

        Map<String, Object> response = new HashMap<>();
        response.put("jobs", jobs);
        response.put("executions", executions);
        response.put("webhooks", webhooks);

        return response;
    }

    /**
     * Per-job statistics.
     */
    @Cacheable(value = "jobStats", key = "#jobId")
    public Map<String, Object> getJobStats(Long jobId) {

        long total = executionRepository.countByJobsId(jobId);

        long success = executionRepository.countByJobsIdAndStatus(
                jobId,
                ExecutionStatus.SUCCESS
        );

        long failed = executionRepository.countByJobsIdAndStatus(
                jobId,
                ExecutionStatus.FAILED
        );

        long running = executionRepository.countByJobsIdAndStatus(
                jobId,
                ExecutionStatus.STARTED
        );

        double rate = total > 0
                ? (double) success / total * 100
                : 0;

        Map<String, Object> stats = new HashMap<>();

        stats.put("jobId", jobId);
        stats.put("total", total);
        stats.put("success", success);
        stats.put("failed", failed);
        stats.put("running", running);
        stats.put("successRate", Math.round(rate * 10.0) / 10.0);

        return stats;
    }
}

