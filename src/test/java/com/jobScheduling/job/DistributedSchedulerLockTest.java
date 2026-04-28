package com.jobScheduling.job;

import com.jobScheduling.job.scheduler.DistributedSchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DistributedSchedulerLockTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private DistributedSchedulerLock lock;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        lock = new DistributedSchedulerLock(redis);
    }

    @Test
    void tryAcquire_returnsTrue_whenRedisSetSucceeds() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        assertThat(lock.tryAcquire()).isTrue();
    }

    @Test
    void tryAcquire_returnsFalse_whenLockAlreadyHeld() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        assertThat(lock.tryAcquire()).isFalse();
    }

    @Test
    void tryAcquire_returnsFalse_whenRedisReturnsNull() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(null);

        assertThat(lock.tryAcquire()).isFalse();
    }

    @Test
    void instanceId_isUniquePerInstance() {
        DistributedSchedulerLock lock2 = new DistributedSchedulerLock(redis);
        assertThat(lock.getInstanceId()).isNotEqualTo(lock2.getInstanceId());
    }

    @Test
    void release_executesLuaScript() {
        when(redis.execute(any(), anyList(), any())).thenReturn(1L);

        lock.release();

        verify(redis).execute(any(), anyList(), any());
    }

    @Test
    void release_doesNotThrow_whenRedisUnavailable() {
        when(redis.execute(any(), anyList(), any()))
                .thenThrow(new RuntimeException("Redis down"));

        // Should swallow the exception and log a warning
        lock.release();
    }
}
