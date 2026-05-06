package com.jobScheduling.job.webhook;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    Page<WebhookDelivery> findByWebhookId(Long webhookId, Pageable pageable);

    /** Fetch deliveries that are PENDING or FAILED and ready for retry */
    @Query(value = """
            SELECT * FROM webhook_deliveries
            WHERE status IN ('PENDING', 'FAILED')
              AND attempt_count < max_attempts
              AND (next_retry_at IS NULL OR next_retry_at <= :now)
            ORDER BY created_at ASC
            LIMIT 100
            """, nativeQuery = true)
    List<WebhookDelivery> findDueForRetry(@Param("now") Timestamp now);

    long countByWebhookIdAndStatus(Long webhookId, String status);

    long countByStatus(String status);

    @Query("SELECT d FROM WebhookDelivery d WHERE d.webhook.id IN " +
           "(SELECT w.id FROM Webhook w WHERE w.jobId = :jobId) " +
           "ORDER BY d.createdAt DESC")
    Page<WebhookDelivery> findByJobId(@Param("jobId") Long jobId, Pageable pageable);
}
