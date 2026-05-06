package com.jobScheduling.job;

import com.jobScheduling.job.dto.JobsDTO;
import com.jobScheduling.job.entity.JobStatus;
import com.jobScheduling.job.entity.Jobs;
import com.jobScheduling.job.exception.JobNotFoundException;
import com.jobScheduling.job.repository.JobsRepository;
import com.jobScheduling.job.service.JobLifecycleService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobLifecycleServiceTest {

    @Mock
    private JobsRepository jobsRepository;

    private JobLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new JobLifecycleService(jobsRepository, new SimpleMeterRegistry());
    }

    // ── Pause tests ───────────────────────────────────────────────────────────

    @Test
    void pause_changesStatusToPaused() {
        Jobs job = activeJob(1L, "0 * * * * *");
        when(jobsRepository.findById(1L)).thenReturn(Optional.of(job));
        when(jobsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        JobsDTO result = service.pause(1L);

        assertThat(result.getStatus()).isEqualTo("PAUSED");
        verify(jobsRepository).save(argThat(j -> j.getStatus() == JobStatus.PAUSED));
    }

    @Test
    void pause_isIdempotent_whenAlreadyPaused() {
        Jobs job = activeJob(1L, "0 * * * * *");
        job.setStatus(JobStatus.PAUSED);
        when(jobsRepository.findById(1L)).thenReturn(Optional.of(job));

        // Should not throw; returns current state
        JobsDTO result = service.pause(1L);
        assertThat(result.getStatus()).isEqualTo("PAUSED");
    }

    @Test
    void pause_throwsWhenJobNotFound() {
        when(jobsRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.pause(99L))
                .isInstanceOf(JobNotFoundException.class);
    }

    @Test
    void pause_throwsWhenJobDisabled() {
        Jobs job = activeJob(1L, "0 * * * * *");
        job.setStatus(JobStatus.DISABLED);
        when(jobsRepository.findById(1L)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.pause(1L))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── Resume tests ──────────────────────────────────────────────────────────

    @Test
    void resume_changesStatusToActive() {
        Jobs job = activeJob(2L, "0 0 * * * *");
        job.setStatus(JobStatus.PAUSED);
        when(jobsRepository.findById(2L)).thenReturn(Optional.of(job));
        when(jobsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        JobsDTO result = service.resume(2L);

        assertThat(result.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void resume_recalculatesNextFireTime() {
        Jobs job = activeJob(2L, "0 * * * * *"); // every minute
        job.setStatus(JobStatus.PAUSED);
        job.setNextFireTime(null); // simulate cleared fire time

        when(jobsRepository.findById(2L)).thenReturn(Optional.of(job));
        when(jobsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.resume(2L);

        // nextFireTime should have been set to a future time
        verify(jobsRepository).save(argThat(j ->
                j.getNextFireTime() != null &&
                j.getNextFireTime().toInstant().isAfter(java.time.Instant.now().minusSeconds(10))
        ));
    }

    @Test
    void resume_throwsWhenJobDisabled() {
        Jobs job = activeJob(3L, "0 * * * * *");
        job.setStatus(JobStatus.DISABLED);
        when(jobsRepository.findById(3L)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.resume(3L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DISABLED");
    }

    @Test
    void resume_isIdempotent_whenAlreadyActive() {
        Jobs job = activeJob(4L, "0 * * * * *");
        when(jobsRepository.findById(4L)).thenReturn(Optional.of(job));

        JobsDTO result = service.resume(4L);
        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        verify(jobsRepository, never()).save(any()); // no DB write needed
    }

    // ── TriggerNow tests ──────────────────────────────────────────────────────

    @Test
    void triggerNow_setsNextFireTimeToNow() {
        Jobs job = activeJob(5L, "0 0 8 * * *"); // normally fires at 8am
        when(jobsRepository.findById(5L)).thenReturn(Optional.of(job));
        when(jobsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.triggerNow(5L);

        verify(jobsRepository).save(argThat(j -> {
            long nowMs = System.currentTimeMillis();
            long fireMs = j.getNextFireTime().getTime();
            return Math.abs(nowMs - fireMs) < 3000; // within 3 seconds of now
        }));
    }

    @Test
    void triggerNow_throwsWhenJobDisabled() {
        Jobs job = activeJob(6L, "0 * * * * *");
        job.setStatus(JobStatus.DISABLED);
        when(jobsRepository.findById(6L)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.triggerNow(6L))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Jobs activeJob(Long id, String cron) {
        Jobs job = new Jobs();
        try {
            var f = Jobs.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(job, id);
        } catch (Exception ignored) {}
        job.setName("test-job-" + id);
        job.setCronExpression(cron);
        job.setStatus(JobStatus.ACTIVE);
        job.setTarget("https://test.internal/" + id);
        return job;
    }
}
