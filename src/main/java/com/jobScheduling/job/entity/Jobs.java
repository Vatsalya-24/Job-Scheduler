package com.jobScheduling.job.entity;

import jakarta.persistence.*;
import java.sql.Timestamp;
import java.time.Instant;

@Entity
@Table(
    name = "jobs",
    indexes = {
        @Index(name = "idx_jobs_next_fire_time", columnList = "next_fire_time"),
        @Index(name = "idx_jobs_status_fire_time", columnList = "status, next_fire_time")
    }
)
public class Jobs {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "cron_expression", nullable = false, length = 255)
    private String cronExpression;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    @Column(nullable = false, length = 255)
    private String target;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    @Column(name = "next_fire_time")
    private Timestamp nextFireTime;

    @Version
    private Long version;  // Optimistic locking — prevents concurrent update races

    @PrePersist
    protected void onCreate() {
        this.createdAt = Timestamp.from(Instant.now());
        if (this.status == null) {
            this.status = JobStatus.ACTIVE;
        }
    }

    public Jobs() {}

    public Jobs(String name, String cronExpression, JobStatus status, String target, Timestamp nextFireTime) {
        this.name = name;
        this.cronExpression = cronExpression;
        this.status = status;
        this.target = target;
        this.nextFireTime = nextFireTime;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }
    public Timestamp getCreatedAt() { return createdAt; }
    public Timestamp getNextFireTime() { return nextFireTime; }
    public void setNextFireTime(Timestamp nextFireTime) { this.nextFireTime = nextFireTime; }

    @Override
    public String toString() {
        return "Jobs{id=" + id + ", name='" + name + "', status=" + status + "}";
    }
}
