package com.jobScheduling.job.webhook;

import jakarta.persistence.*;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Tracks every delivery attempt for a webhook.
 * Allows full audit trail: "why did this callback fail at 3am?"
 */
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "webhook_id", nullable = false)
    private Webhook webhook;

    @Column(name = "job_execution_id")
    private Long jobExecutionId;

    @Column(name = "event_type", length = 20)
    private String eventType;   // STARTED | SUCCESS | FAILED

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;     // JSON body that was sent

    @Column(name = "status", length = 20)
    private String status;      // PENDING | DELIVERED | FAILED | EXHAUSTED

    @Column(name = "http_status_code")
    private Integer httpStatusCode;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "attempt_count")
    private int attemptCount = 0;

    @Column(name = "max_attempts")
    private int maxAttempts = 5;

    @Column(name = "created_at", updatable = false)
    private Timestamp createdAt;

    @Column(name = "delivered_at")
    private Timestamp deliveredAt;

    @Column(name = "next_retry_at")
    private Timestamp nextRetryAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Timestamp.from(Instant.now());
        this.status = "PENDING";
    }

    public WebhookDelivery() {}

    public Long getId() { return id; }
    public Webhook getWebhook() { return webhook; }
    public void setWebhook(Webhook webhook) { this.webhook = webhook; }
    public Long getJobExecutionId() { return jobExecutionId; }
    public void setJobExecutionId(Long jobExecutionId) { this.jobExecutionId = jobExecutionId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getHttpStatusCode() { return httpStatusCode; }
    public void setHttpStatusCode(Integer httpStatusCode) { this.httpStatusCode = httpStatusCode; }
    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public Timestamp getCreatedAt() { return createdAt; }
    public Timestamp getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Timestamp deliveredAt) { this.deliveredAt = deliveredAt; }
    public Timestamp getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Timestamp nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
