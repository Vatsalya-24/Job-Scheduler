package com.jobScheduling.job.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Checks Redis connectivity and measures round-trip latency.
 * A latency > 100ms is flagged as degraded (still UP but with a warning).
 */
@Component("redis")
public class RedisHealthIndicator implements HealthIndicator {

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisHealthIndicator(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Health health() {
        try {
            long start = System.currentTimeMillis();
            String pong = (String) redisTemplate.getConnectionFactory()
                    .getConnection()
                    .ping();
            long latencyMs = System.currentTimeMillis() - start;

            Health.Builder builder = "PONG".equalsIgnoreCase(pong)
                    ? Health.up()
                    : Health.down().withDetail("response", pong);

            builder.withDetail("latencyMs", latencyMs);

            if (latencyMs > 100) {
                builder.withDetail("warning", "High Redis latency: " + latencyMs + "ms");
            }

            return builder.build();

        } catch (Exception ex) {
            return Health.down()
                    .withDetail("error", ex.getMessage())
                    .build();
        }
    }
}
