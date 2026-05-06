package com.jobScheduling.job.entity;

import jakarta.persistence.*;
import java.sql.Timestamp;

/**
 * Records every webhook delivery attempt — success or failure.
 *
 * Design change: originally only logged failures (so the table was always empty
 * when webhooks were delivering successfully). Now logs ALL attempts so you get
 * a full audit trail: attempt 1 succeeded, attempt 2 got a 503, attempt 3 succeeded.
 *
 * Schema:
 *   id             — auto PK
 *   execution_id   — FK to job_executions
 *   attempt_number — 1-based attempt counter
 *   fired_at       — when the attempt was made
 *   success        — true if HTTP 2xx received, false otherwise
 *   error_message  — null on success; exception message or "HTTP 503" on failure
 */
@Entity
@Table(name = "retry_log", indexes = {
        @Index(name = "idx_retry_log_execution_id", columnList = "execution_id"),
        @Index(name = "idx_retry_log_success",      columnList = "success")
})
public class RetryLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false)
    private JobExecution executionId;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Column(name = "fired_at")
    private Timestamp firedAt;

    /** true = HTTP 2xx received; false = network error or non-2xx status */
    @Column(name = "success", nullable = false)
    private boolean success;

    /** null on success; error description on failure (e.g. "HTTP 503", "Connection refused") */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    public RetryLog() {}

    public RetryLog(JobExecution executionId, Integer attemptNumber,
                    Timestamp firedAt, boolean success, String errorMessage) {
        this.executionId   = executionId;
        this.attemptNumber = attemptNumber;
        this.firedAt       = firedAt;
        this.success       = success;
        this.errorMessage  = errorMessage;
    }

    public Long getId()                        { return id; }
    public JobExecution getExecutionId()       { return executionId; }
    public void setExecutionId(JobExecution e) { this.executionId = e; }
    public Integer getAttemptNumber()          { return attemptNumber; }
    public void setAttemptNumber(Integer n)    { this.attemptNumber = n; }
    public Timestamp getFiredAt()              { return firedAt; }
    public void setFiredAt(Timestamp t)        { this.firedAt = t; }
    public boolean isSuccess()                 { return success; }
    public void setSuccess(boolean success)    { this.success = success; }
    public String getErrorMessage()            { return errorMessage; }
    public void setErrorMessage(String msg)    { this.errorMessage = msg; }
}