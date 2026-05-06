package com.jobScheduling.job;

import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Provides a mock StringRedisTemplate for integration tests.
 *
 * WHY: application-test.yml excludes Redis auto-configuration to keep tests fast
 * (no real Redis needed for most tests). But JobExecutionService now injects
 * StringRedisTemplate for the job-existence cache. Without this config, the
 * Spring context fails to start for integration tests.
 *
 * This mock returns null for all Redis reads (cache misses), causing the service
 * to fall through to the DB — correct behaviour for tests.
 */
@TestConfiguration
public class TestRedisConfig {

    @Bean
    @Primary
    public StringRedisTemplate stringRedisTemplate() {
        StringRedisTemplate mock = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = Mockito.mock(ValueOperations.class);
        Mockito.when(mock.opsForValue()).thenReturn(valueOps);
        // Cache miss by default — service falls through to DB
        Mockito.when(valueOps.get(Mockito.anyString())).thenReturn(null);
        return mock;
    }
}
