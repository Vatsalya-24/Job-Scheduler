package com.jobScheduling.job.webhook;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

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
@Getter
@Entity
@Table(name = "webhooks", indexes = {
        @Index(name = "idx_webhooks_job_id", columnList = "job_id"),
        @Index(name = "idx_webhooks_active", columnList = "active, job_id")
})
public class Webhook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Setter
    @Column(nullable = false, length = 2048)
    private String url;

    /** HMAC-SHA256 secret — used to sign payloads so the receiver can verify authenticity */
    @Setter
    @Column(name = "secret_key", length = 255)
    private String secretKey;

    @Setter
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    /** Events to deliver: STARTED, SUCCESS, FAILED, ALL */
    @Setter
    @Column(name = "event_filter", length = 20)
    private String eventFilter = "ALL";

    @PrePersist
    protected void onCreate() {
        this.createdAt = Timestamp.from(Instant.now());
    }

    public Webhook() {}

}
