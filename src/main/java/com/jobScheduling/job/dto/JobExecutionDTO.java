package com.jobScheduling.job.dto;

import jakarta.validation.constraints.NotBlank;
import java.sql.Timestamp;

/**
 * DTO for recording job execution results.
 *
 * NOTE: jobId has no @NotNull here because the controller sets it from the
 * path variable AFTER deserialization:
 *   dto.setJobId(id);  ← set in JobsController before calling the service
 *
 * If @NotNull were on jobId, the validation would fire before the controller
 * sets it, causing 400 Bad Request on every execution recording request.
 */
public class JobExecutionDTO {

    private Long id;

    @NotBlank(message = "Status is required")
    private String status;

    private Timestamp startedAt;
    private Timestamp finishedAt;
    private String errorMessage;

    // No @NotNull — set from @PathVariable in the controller, not from request body
    private Long jobId;

    public JobExecutionDTO() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Timestamp getStartedAt() { return startedAt; }
    public void setStartedAt(Timestamp startedAt) { this.startedAt = startedAt; }
    public Timestamp getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Timestamp finishedAt) { this.finishedAt = finishedAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }
}
