package com.jobScheduling.job.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Publishes execution lifecycle events to connected WebSocket clients (dashboard).
 *
 * Every time a job fires, starts, succeeds, or fails — the dashboard sees it
 * in real-time without any polling.
 */
@Component
public class ExecutionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEventPublisher.class);

    private final SimpMessagingTemplate messagingTemplate;

    public ExecutionEventPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Broadcast to all dashboard subscribers watching any execution.
     */
    @Async("taskExecutor")
    public void publishExecutionUpdate(Long jobId, Long executionId,
                                       String status, String jobName) {
        Map<String, Object> event = Map.of(
                "type",        "EXECUTION_UPDATE",
                "jobId",       jobId,
                "executionId", executionId,
                "status",      status,
                "jobName",     jobName,
                "timestamp",   Instant.now().toString()
        );

        try {
            // Broadcast to all subscribers of /topic/executions
            messagingTemplate.convertAndSend("/topic/executions", event);

            // Also broadcast to job-specific topic /topic/jobs/{id}
            messagingTemplate.convertAndSend("/topic/jobs/" + jobId, event);

            log.debug("Published WS event jobId={} status={}", jobId, status);
        } catch (Exception e) {
            // WS failure must never break the main execution flow
            log.warn("WebSocket publish failed: {}", e.getMessage());
        }
    }

    /**
     * Push live system metrics to the dashboard metrics panel.
     * Called by the MetricsBroadcaster every 5 seconds.
     */
    @Async("taskExecutor")
    public void publishMetrics(Map<String, Object> metrics) {
        try {
            messagingTemplate.convertAndSend("/topic/metrics", metrics);
        } catch (Exception e) {
            log.warn("Metrics WS publish failed: {}", e.getMessage());
        }
    }
}
