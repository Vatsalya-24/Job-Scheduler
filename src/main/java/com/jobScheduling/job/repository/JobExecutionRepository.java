package com.jobScheduling.job.repository;

import com.jobScheduling.job.entity.ExecutionStatus;
import com.jobScheduling.job.entity.JobExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public interface JobExecutionRepository extends JpaRepository<JobExecution, Long> {

    Page<JobExecution> findByJobsId(Long jobId, Pageable pageable);

    long countByJobsId(Long jobId);

    long countByJobsIdAndStatus(Long jobId, ExecutionStatus status);

    long countByStatus(ExecutionStatus status);

    @Query("""
            SELECT e FROM JobExecution e
            WHERE e.jobs.id = :jobId
              AND e.status = 'FAILED'
              AND e.startedAt >= :since
            ORDER BY e.startedAt DESC
            """)
    List<JobExecution> findRecentFailures(
            @Param("jobId") Long jobId,
            @Param("since") Timestamp since);

    /** Recent executions for the live feed on the dashboard */
    @Query(value = """
            SELECT * FROM job_executions
            ORDER BY started_at DESC
            LIMIT 50
            """, nativeQuery = true)
    List<JobExecution> findRecent50();
}
