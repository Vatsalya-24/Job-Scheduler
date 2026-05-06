package com.jobScheduling.job.webhook;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public interface WebhookRepository extends JpaRepository<Webhook, Long> {

    List<Webhook> findByJobIdAndActiveTrue(Long jobId);

    List<Webhook> findByJobId(Long jobId);
}

