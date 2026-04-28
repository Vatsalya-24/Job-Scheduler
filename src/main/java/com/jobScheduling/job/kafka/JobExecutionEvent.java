package com.jobScheduling.job.kafka;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Event published when a job execution completes (success or failure).
 * Consumed by the result-processor to persist execution records.
 */
public class JobExecutionEvent {

    private final Long jobId;
    private final Long executionId;
    private final String status;          // STARTED | SUCCESS | FAILED | TIMEOUT
    private final Instant startedAt;
    private final Instant finishedAt;
    private final String errorMessage;
    private final int attemptNumber;

    @JsonCreator
    public JobExecutionEvent(
            @JsonProperty("jobId") Long jobId,
            @JsonProperty("executionId") Long executionId,
            @JsonProperty("status") String status,
            @JsonProperty("startedAt") Instant startedAt,
            @JsonProperty("finishedAt") Instant finishedAt,
            @JsonProperty("errorMessage") String errorMessage,
            @JsonProperty("attemptNumber") int attemptNumber) {
        this.jobId = jobId;
        this.executionId = executionId;
        this.status = status;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.errorMessage = errorMessage;
        this.attemptNumber = attemptNumber;
    }

    public Long getJobId() { return jobId; }
    public Long getExecutionId() { return executionId; }
    public String getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getErrorMessage() { return errorMessage; }
    public int getAttemptNumber() { return attemptNumber; }
}
