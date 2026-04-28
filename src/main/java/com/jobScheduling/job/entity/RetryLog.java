package com.jobScheduling.job.entity;

import jakarta.persistence.*;
import java.sql.Timestamp;

@Entity
@Table(name = "retry_log")
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
    private Timestamp firedAt;    // fixed: java.sql.Timestamp (not java.security.Timestamp)

    public RetryLog() {}

    public RetryLog(JobExecution executionId, Integer attemptNumber, Timestamp firedAt) {
        this.executionId = executionId;
        this.attemptNumber = attemptNumber;
        this.firedAt = firedAt;
    }

    public Long getId() { return id; }
    public JobExecution getExecutionId() { return executionId; }
    public void setExecutionId(JobExecution executionId) { this.executionId = executionId; }
    public Integer getAttemptNumber() { return attemptNumber; }
    public void setAttemptNumber(Integer attemptNumber) { this.attemptNumber = attemptNumber; }
    public Timestamp getFiredAt() { return firedAt; }
    public void setFiredAt(Timestamp firedAt) { this.firedAt = firedAt; }
}
