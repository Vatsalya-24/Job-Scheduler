package com.jobScheduling.job.controller;

import com.jobScheduling.job.dto.JobExecutionDTO;
import com.jobScheduling.job.dto.JobsDTO;
import com.jobScheduling.job.service.JobExecutionService;
import com.jobScheduling.job.service.JobLifecycleService;
import com.jobScheduling.job.service.JobsService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobsController {

    private final JobsService jobsService;
    private final JobExecutionService jobExecutionService;
    private final JobLifecycleService lifecycleService;

    public JobsController(JobsService jobsService,
                          JobExecutionService jobExecutionService,
                          JobLifecycleService lifecycleService) {
        this.jobsService         = jobsService;
        this.jobExecutionService = jobExecutionService;
        this.lifecycleService    = lifecycleService;
    }

    @PostMapping
    @RateLimiter(name = "jobApi", fallbackMethod = "rateLimitFallback")
    public ResponseEntity<JobsDTO> createJob(@Valid @RequestBody JobsDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(jobsService.createNewJob(dto));
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobsDTO> getJob(@PathVariable Long id) {
        return ResponseEntity.ok(jobsService.getJobById(id));
    }

    @GetMapping
    public ResponseEntity<Page<JobsDTO>> listJobs(
            @PageableDefault(size = 50, sort = "nextFireTime") Pageable pageable) {
        return ResponseEntity.ok(jobsService.getAllJobs(pageable));
    }

    @PutMapping("/{id}")
    public ResponseEntity<JobsDTO> updateJob(@PathVariable Long id,
                                              @Valid @RequestBody JobsDTO dto) {
        return ResponseEntity.ok(jobsService.updateJob(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> disableJob(@PathVariable Long id) {
        jobsService.disableJob(id);
        return ResponseEntity.noContent().build();
    }

    /** Pause — stops firing, keeps schedule */
    @PostMapping("/{id}/pause")
    public ResponseEntity<JobsDTO> pause(@PathVariable Long id) {
        return ResponseEntity.ok(lifecycleService.pause(id));
    }

    /** Resume — recalculates next_fire_time from NOW, skips missed windows */
    @PostMapping("/{id}/resume")
    public ResponseEntity<JobsDTO> resume(@PathVariable Long id) {
        return ResponseEntity.ok(lifecycleService.resume(id));
    }

    /** Trigger now — fires in next 1s scheduler poll cycle */
    @PostMapping("/{id}/trigger")
    public ResponseEntity<Map<String, Object>> triggerNow(@PathVariable Long id) {
        JobsDTO job = lifecycleService.triggerNow(id);
        return ResponseEntity.accepted().body(Map.of(
                "message",      "Job triggered for immediate execution",
                "jobId",        id,
                "jobName",      job.getName(),
                "nextFireTime", job.getNextFireTime() != null
                                ? job.getNextFireTime().toString() : "now"
        ));
    }

    /** Record execution result — async via Kafka, returns 202 immediately */
    @PostMapping("/{id}/executions")
    @RateLimiter(name = "jobApi", fallbackMethod = "rateLimitFallbackExecution")
    public CompletableFuture<ResponseEntity<Map<String, String>>> recordExecution(
            @PathVariable Long id,
            @Valid @RequestBody JobExecutionDTO dto) {
        dto.setJobId(id);
        return jobExecutionService.recordExecutionAsync(dto)
                .thenApply(v -> ResponseEntity
                        .accepted()
                        .<Map<String, String>>body(Map.of(
                                "status",  "queued",
                                "message", "Execution queued for processing",
                                "jobId",   String.valueOf(id)
                        )));
    }

    @GetMapping("/{id}/executions")
    public ResponseEntity<Page<JobExecutionDTO>> getExecutions(
            @PathVariable Long id,
            @PageableDefault(size = 20, sort = "startedAt") Pageable pageable) {
        return ResponseEntity.ok(jobExecutionService.getExecutionsByJob(id, pageable));
    }

    public ResponseEntity<JobsDTO> rateLimitFallback(JobsDTO dto, Throwable t) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
    }

    public CompletableFuture<ResponseEntity<Map<String, String>>> rateLimitFallbackExecution(
            Long id, JobExecutionDTO dto, Throwable t) {
        return CompletableFuture.completedFuture(
                ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body(Map.of("status", "rate_limited",
                                     "message", "Too many requests — retry after 1s")));
    }
}
