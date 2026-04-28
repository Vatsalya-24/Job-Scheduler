package com.jobScheduling.job;

import com.jobScheduling.job.entity.JobStatus;
import com.jobScheduling.job.entity.Jobs;
import com.jobScheduling.job.kafka.KafkaProducerService;
import com.jobScheduling.job.repository.JobExecutionRepository;
import com.jobScheduling.job.repository.JobsRepository;
import com.jobScheduling.job.scheduler.DistributedSchedulerLock;
import com.jobScheduling.job.scheduler.JobSchedulerPoller;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobSchedulerPollerTest {

    @Mock private JobsRepository jobsRepository;
    @Mock private JobExecutionRepository jobExecutionRepository;
    @Mock private KafkaProducerService kafkaProducerService;
    @Mock private DistributedSchedulerLock distributedLock;

    private JobSchedulerPoller poller;

    @BeforeEach
    void setUp() {
        poller = new JobSchedulerPoller(
                jobsRepository, jobExecutionRepository,
                kafkaProducerService, distributedLock,
                new SimpleMeterRegistry()
        );
    }

    @Test
    void pollAndDispatch_skipsWhenLockNotAcquired() {
        when(distributedLock.tryAcquire()).thenReturn(false);

        poller.pollAndDispatch();

        verifyNoInteractions(jobsRepository);
        verifyNoInteractions(kafkaProducerService);
        verify(distributedLock, never()).release();
    }

    @Test
    void pollAndDispatch_releasesLockEvenWhenNoDueJobs() {
        when(distributedLock.tryAcquire()).thenReturn(true);
        when(jobsRepository.findJobsDueToFire(any(), any())).thenReturn(List.of());

        poller.pollAndDispatch();

        verify(distributedLock).release();
        verifyNoInteractions(kafkaProducerService);
    }

    @Test
    void pollAndDispatch_releasesLockEvenOnException() {
        when(distributedLock.tryAcquire()).thenReturn(true);
        when(jobsRepository.findJobsDueToFire(any(), any()))
                .thenThrow(new RuntimeException("DB error"));

        try {
            poller.pollAndDispatch();
        } catch (Exception ignored) {}

        // Lock MUST be released regardless of failure
        verify(distributedLock).release();
    }

    @Test
    void pollAndDispatch_onlyOneInstanceFires_whenBothAcquireSimultaneously() {
        // Simulate race: first call wins, second loses
        when(distributedLock.tryAcquire())
                .thenReturn(true)    // first instance wins
                .thenReturn(false);  // second instance loses

        when(jobsRepository.findJobsDueToFire(any(), any())).thenReturn(List.of());

        poller.pollAndDispatch();  // wins lock
        poller.pollAndDispatch();  // loses lock

        // DB should only be queried once (by the winner)
        verify(jobsRepository, times(1)).findJobsDueToFire(any(), any());
    }
}
