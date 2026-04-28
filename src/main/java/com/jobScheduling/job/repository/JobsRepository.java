package com.jobScheduling.job.repository;

import com.jobScheduling.job.entity.JobStatus;
import com.jobScheduling.job.entity.Jobs;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public interface JobsRepository extends JpaRepository<Jobs, Long> {

    @Query(value = """
            SELECT * FROM jobs
            WHERE status = :#{#status.name()}
              AND next_fire_time <= :now
            ORDER BY next_fire_time ASC
            LIMIT 500
            """, nativeQuery = true)
    List<Jobs> findJobsDueToFire(
            @Param("status") JobStatus status,
            @Param("now") Timestamp now);

    Page<Jobs> findByStatus(JobStatus status, Pageable pageable);

    long countByStatus(JobStatus status);

    boolean existsById(Long id);
}
