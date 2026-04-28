package com.jobScheduling.job.webhook;

import jakarta.persistence.*;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * A Webhook is a URL registered by a developer to receive HTTP POST callbacks
 * whenever a job execution completes (success or failure).
 *
 * Resume talking point: "Built a webhook delivery system supporting 50k+ job
 * executions/sec with guaranteed at-least-once delivery via Kafka and
 * exponential backoff retry."
 */
@Entity
@Table(name = "webhooks", indexes = {
        @Index(name = "idx_webhooks_job_id", columnList = "job_id"),
        @Index(name = "idx_webhooks_active", columnList = "active, job_id")
})
public class Webhook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(nullable = false, length = 2048)
    private String url;

    /** HMAC-SHA256 secret — used to sign payloads so the receiver can verify authenticity */
    @Column(name = "secret_key", length = 255)
    private String secretKey;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    /** Events to deliver: STARTED, SUCCESS, FAILED, ALL */
    @Column(name = "event_filter", length = 20)
    private String eventFilter = "ALL";

    @PrePersist
    protected void onCreate() {
        this.createdAt = Timestamp.from(Instant.now());
    }

    public Webhook() {}

    public Long getId() { return id; }
    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Timestamp getCreatedAt() { return createdAt; }
    public String getEventFilter() { return eventFilter; }
    public void setEventFilter(String eventFilter) { this.eventFilter = eventFilter; }
}
