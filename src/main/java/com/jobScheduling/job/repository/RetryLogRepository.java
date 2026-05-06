package com.jobScheduling.job.repository;

import com.jobScheduling.job.entity.RetryLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RetryLogRepository extends JpaRepository<RetryLog, Long> {

    List<RetryLog> findByExecutionIdIdOrderByAttemptNumberAsc(Long executionId);

    long countByExecutionIdId(Long executionId);
}
