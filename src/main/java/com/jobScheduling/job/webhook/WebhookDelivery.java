package com.jobScheduling.job.webhook;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Tracks every delivery attempt for a webhook.
 * Allows full audit trail: "why did this callback fail at 3am?"
 */
@Getter
@Entity
@Table(name = "webhook_deliveries", indexes = {
        @Index(name = "idx_webhook_deliveries_webhook_id", columnList = "webhook_id"),
        @Index(name = "idx_webhook_deliveries_status", columnList = "status"),
        @Index(name = "idx_webhook_deliveries_next_retry", columnList = "next_retry_at")
})
public class WebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "webhook_id", nullable = false)
    private Webhook webhook;

    @Setter
    @Column(name = "job_execution_id")
    private Long jobExecutionId;

    @Setter
    @Column(name = "event_type", length = 20)
    private String eventType;   // STARTED | SUCCESS | FAILED

    @Setter
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;     // JSON body that was sent

    @Setter
    @Column(name = "status", length = 20)
    private String status;      // PENDING | DELIVERED | FAILED | EXHAUSTED

    @Setter
    @Column(name = "http_status_code")
    private Integer httpStatusCode;

    @Setter
    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Setter
    @Column(name = "attempt_count")
    private int attemptCount = 0;

    @Setter
    @Column(name = "max_attempts")
    private int maxAttempts = 5;

    @Column(name = "created_at", updatable = false)
    private Timestamp createdAt;

    @Setter
    @Column(name = "delivered_at")
    private Timestamp deliveredAt;

    @Setter
    @Column(name = "next_retry_at")
    private Timestamp nextRetryAt;

    @Setter
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Timestamp.from(Instant.now());
        this.status = "PENDING";
    }

    public WebhookDelivery() {}

}
