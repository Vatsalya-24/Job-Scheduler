package com.jobScheduling.job.entity;

import jakarta.persistence.*;
import java.sql.Timestamp;

@Entity
@Table(
    name = "job_executions",
    indexes = {
        @Index(name = "idx_job_executions_job_id", columnList = "job_id"),
        @Index(name = "idx_job_executions_status", columnList = "status"),
        @Index(name = "idx_job_executions_started_at", columnList = "started_at")
    }
)
public class JobExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long  id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExecutionStatus status;

    @Column(name = "started_at")
    private Timestamp startedAt;

    @Column(name = "finished_at")
    private Timestamp finishedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @ManyToOne(fetch = FetchType.LAZY)   // LAZY — don't eagerly load job for every execution
    @JoinColumn(name = "job_id", nullable = false)
    private Jobs jobs;

    public JobExecution() {}

    public JobExecution(ExecutionStatus status, Timestamp startedAt,
                        Timestamp finishedAt, String errorMessage, Jobs jobs) {
        this.status = status;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.errorMessage = errorMessage;
        this.jobs = jobs;
    }

    public Long  getId() { return id; }
    public ExecutionStatus getStatus() { return status; }
    public void setStatus(ExecutionStatus status) { this.status = status; }
    public Timestamp getStartedAt() { return startedAt; }
    public void setStartedAt(Timestamp startedAt) { this.startedAt = startedAt; }
    public Timestamp getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Timestamp finishedAt) { this.finishedAt = finishedAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Jobs getJobs() { return jobs; }
    public void setJobs(Jobs jobs) { this.jobs = jobs; }
}
