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
     * Logs a delivery attempt — called by WebhookService on EVERY attempt,
     * regardless of outcome. outcomeError=null means the attempt succeeded.
     *
     * @param execution     the job execution that produced the webhook event
     * @param attemptNumber which attempt number this is (starts at 1)
     * @param outcomeError  null on success; error string on failure
     */
    @Transactional
    public RetryLog logRetry(JobExecution execution, Integer attemptNumber, String outcomeError) {
        boolean success = (outcomeError == null);
        RetryLog retryLog = new RetryLog(
                execution, attemptNumber,
                Timestamp.from(Instant.now()),
                success,
                outcomeError
        );
        RetryLog saved = retryLogRepository.save(retryLog);
        log.debug("retry_log written: id={} executionId={} attempt={} success={}",
                saved.getId(), execution.getId(), attemptNumber, success);
        return saved;
    }

    /** Convenience overload for legacy callers that only track failures */
    @Transactional
    public RetryLog logRetry(JobExecution execution, Integer attemptNumber) {
        return logRetry(execution, attemptNumber, null);
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