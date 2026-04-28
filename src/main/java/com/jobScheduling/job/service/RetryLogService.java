package com.jobScheduling.job.service;

import com.jobScheduling.job.entity.JobExecution;
import com.jobScheduling.job.entity.RetryLog;
import com.jobScheduling.job.repository.RetryLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Writes retry attempt records into the retry_log table.
 *
 * BUG 3 FIX: This service existed but logRetry() was never called.
 * It is now called from WebhookService.handleFailure() on every failed
 * webhook delivery attempt.
 *
 * retry_log schema:
 *   id             — auto-generated PK
 *   execution_id   — FK to job_executions (the execution that triggered the webhook)
 *   attempt_number — which retry attempt this is (1, 2, 3...)
 *   fired_at       — when the attempt was made
 */
@Service
public class RetryLogService {

    private static final Logger log = LoggerFactory.getLogger(RetryLogService.class);

    private final RetryLogRepository retryLogRepository;

    public RetryLogService(RetryLogRepository retryLogRepository) {
        this.retryLogRepository = retryLogRepository;
    }

    /**
     * Logs a retry attempt. Called by WebhookService on every failed delivery.
     *
     * @param execution     the job execution that produced the webhook event
     * @param attemptNumber which attempt number this is (starts at 1)
     */
    @Transactional
    public RetryLog logRetry(JobExecution execution, Integer attemptNumber) {
        RetryLog retryLog = new RetryLog(execution, attemptNumber, Timestamp.from(Instant.now()));
        RetryLog saved = retryLogRepository.save(retryLog);
        log.debug("retry_log written: id={} executionId={} attempt={}",
                saved.getId(), execution.getId(), attemptNumber);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<RetryLog> getRetriesForExecution(Long executionId) {
        return retryLogRepository.findByExecutionIdIdOrderByAttemptNumberAsc(executionId);
    }

    @Transactional(readOnly = true)
    public long countRetries(Long executionId) {
        return retryLogRepository.countByExecutionIdId(executionId);
    }
}
