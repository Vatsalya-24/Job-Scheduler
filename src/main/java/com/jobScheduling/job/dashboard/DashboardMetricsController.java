package com.jobScheduling.job.dashboard;

import com.jobScheduling.job.entity.ExecutionStatus;
import com.jobScheduling.job.entity.JobStatus;
import com.jobScheduling.job.repository.JobExecutionRepository;
import com.jobScheduling.job.repository.JobsRepository;
import com.jobScheduling.job.webhook.WebhookDeliveryRepository;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * THE BUG: @Cacheable was placed on a method that returns ResponseEntity<Map<...>>.
 *
 * Spring's @Cacheable stores the RETURN VALUE of the method in Redis.
 * The return value was ResponseEntity — a Spring HTTP wrapper that includes
 * ReadOnlyHttpHeaders (Cache-Control headers, etc.).
 *
 * When Redis reads it back, Jackson tries to deserialize the JSON back into
 * a ResponseEntity object. ResponseEntity has no no-arg constructor and no
 * @JsonCreator — Jackson cannot reconstruct it. Crash on every cache HIT.
 *
 * The irony: caching started working (first call populates Redis) but every
 * subsequent call (the cache hit) threw a 500. So caching made things worse.
 *
 * RULE: Never put @Cacheable on a method that returns ResponseEntity, Page,
 * HttpEntity, or any Spring MVC wrapper type. Only cache plain data objects
 * (Map, DTO, List, primitives). Move @Cacheable to a service layer method
 * that returns the plain data, then wrap it in ResponseEntity in the controller.
 *
 * FIX: Extract the data-building logic into private methods that return plain
 * Map<String, Object>. Cache those Maps in a service. Controller wraps the
 * result in ResponseEntity AFTER retrieval — never touched by the cache.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardMetricsController {

    private final JobsRepository            jobsRepository;
    private final JobExecutionRepository    executionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final DashboardSummaryService   summaryService;

    public DashboardMetricsController(
            JobsRepository jobsRepository,
            JobExecutionRepository executionRepository,
            WebhookDeliveryRepository deliveryRepository,
            DashboardSummaryService summaryService) {
        this.jobsRepository      = jobsRepository;
        this.executionRepository = executionRepository;
        this.deliveryRepository  = deliveryRepository;
        this.summaryService      = summaryService;
    }

    /**
     * Controller returns ResponseEntity — NOT cached.
     * Data comes from summaryService.getSummary() which IS cached (returns plain Map).
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        Map<String, Object> data = summaryService.getSummary();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(10, TimeUnit.SECONDS))
                .body(data);
    }

    @GetMapping("/jobs/{jobId}/stats")
    public ResponseEntity<Map<String, Object>> jobStats(@PathVariable Long jobId) {
        Map<String, Object> data = summaryService.getJobStats(jobId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(10, TimeUnit.SECONDS))
                .body(data);
    }
}