package com.jobScheduling.job.websocket;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Periodically reads Micrometer metrics and broadcasts them to the dashboard
 * via WebSocket. The dashboard's live metrics panel consumes /topic/metrics.
 *
 * Metrics broadcast every 5 seconds:
 *  - Total jobs created
 *  - Jobs fired (scheduler throughput)
 *  - Kafka events sent/failed
 *  - Webhook deliveries delivered/failed
 *  - Active HikariCP connections
 */
@Component
public class MetricsBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(MetricsBroadcaster.class);

    private final MeterRegistry meterRegistry;
    private final ExecutionEventPublisher eventPublisher;

    public MetricsBroadcaster(MeterRegistry meterRegistry,
                               ExecutionEventPublisher eventPublisher) {
        this.meterRegistry = meterRegistry;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedDelay = 5000)
    public void broadcast() {
        try {
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("timestamp", Instant.now().toString());

            // Job counters
            metrics.put("jobsCreated",  safeCount("jobs.created.total"));
            metrics.put("jobsFired",    safeCount("scheduler.jobs.fired"));
            metrics.put("jobsUpdated",  safeCount("jobs.updated.total"));

            // Kafka health
            metrics.put("kafkaEventsSent",   safeCount("kafka.job.events.sent"));
            metrics.put("kafkaEventsFailed", safeCount("kafka.job.events.failed"));
            metrics.put("kafkaConsumed",     safeCount("kafka.consumer.executions.processed"));

            // Webhook stats
            metrics.put("webhooksDelivered", safeCount("webhooks.delivered"));
            metrics.put("webhooksFailed",    safeCount("webhooks.failed"));
            metrics.put("webhooksRetried",   safeCount("webhooks.retried"));

            // Scheduler
            metrics.put("schedulerDueJobs",     safeGauge("scheduler.due_jobs"));
            metrics.put("schedulerPollSkipped", safeCount("scheduler.poll.skipped"));

            // DB pool
            metrics.put("dbActiveConnections", safeGauge("hikaricp.connections.active"));
            metrics.put("dbPendingThreads",    safeGauge("hikaricp.connections.pending"));

            eventPublisher.publishMetrics(metrics);

        } catch (Exception e) {
            log.warn("Metrics broadcast error: {}", e.getMessage());
        }
    }

    private double safeCount(String name) {
        try {
            return Search.in(meterRegistry).name(name)
                    .counters().stream()
                    .mapToDouble(c -> c.count())
                    .sum();
        } catch (Exception e) { return 0; }
    }

    private double safeGauge(String name) {
        try {
            return Search.in(meterRegistry).name(name)
                    .gauges().stream()
                    .mapToDouble(g -> g.value())
                    .sum();
        } catch (Exception e) { return 0; }
    }
}
