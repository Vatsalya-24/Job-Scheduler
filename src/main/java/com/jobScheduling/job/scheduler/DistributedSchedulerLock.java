package com.jobScheduling.job.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Redis-backed distributed lock for the job scheduler poller.
 *
 * Problem it solves:
 *   When running multiple app instances (horizontal scaling), every instance
 *   runs JobSchedulerPoller. Without coordination, all N instances would fire
 *   the same job at the same time — causing duplicate executions.
 *
 * Solution:
 *   Before each poll cycle, an instance tries to acquire a Redis lock
 *   (SET key value NX EX ttl). Only one instance wins. The winner fires
 *   jobs; losers skip. The lock auto-expires so a crashed winner does not
 *   block others indefinitely.
 *
 * Lock TTL = poll interval (1s) + safety buffer (2s) = 3 seconds.
 * If the winner crashes mid-poll, the lock expires within 3s and another
 * instance takes over at the next cycle.
 */
@Component
public class DistributedSchedulerLock {

    private static final Logger log = LoggerFactory.getLogger(DistributedSchedulerLock.class);

    private static final String LOCK_KEY = "job-scheduler:poller-lock";
    private static final Duration LOCK_TTL = Duration.ofSeconds(3);

    // Unique token per instance — needed to release only OUR lock
    private final String instanceId = UUID.randomUUID().toString();

    private final StringRedisTemplate redis;

    public DistributedSchedulerLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Try to acquire the global poller lock.
     * Returns true if this instance is the designated leader for this cycle.
     */
    public boolean tryAcquire() {
        Boolean acquired = redis.opsForValue()
                .setIfAbsent(LOCK_KEY, instanceId, LOCK_TTL);
        boolean won = Boolean.TRUE.equals(acquired);
        if (won) {
            log.debug("Scheduler lock acquired by instance={}", instanceId);
        }
        return won;
    }

    /**
     * Release the lock — only if it still belongs to this instance.
     * Uses a Lua script to make the check+delete atomic (avoids race conditions
     * where TTL expires between our check and our delete).
     */
    public void release() {
        String script = """
                if redis.call('get', KEYS[1]) == ARGV[1] then
                    return redis.call('del', KEYS[1])
                else
                    return 0
                end
                """;
        try {
            redis.execute(
                    new org.springframework.data.redis.core.script.DefaultRedisScript<>(script, Long.class),
                    java.util.List.of(LOCK_KEY),
                    instanceId
            );
            log.debug("Scheduler lock released by instance={}", instanceId);
        } catch (Exception ex) {
            log.warn("Failed to release scheduler lock: {}", ex.getMessage());
        }
    }

    public String getInstanceId() {
        return instanceId;
    }
}
