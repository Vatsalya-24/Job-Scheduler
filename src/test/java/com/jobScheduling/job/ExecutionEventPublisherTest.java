package com.jobScheduling.job;

import com.jobScheduling.job.websocket.ExecutionEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionEventPublisherTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private ExecutionEventPublisher publisher;

    @Test
    void publishExecutionUpdate_sendsToBothTopics() {
        publisher.publishExecutionUpdate(1L, 42L, "SUCCESS", "daily-report");

        // Should publish to the global feed AND job-specific topic
        verify(messagingTemplate).convertAndSend(eq("/topic/executions"), any(Map.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/jobs/1"), any(Map.class));
    }

    @Test
    void publishExecutionUpdate_doesNotThrow_whenMessagingFails() {
        doThrow(new RuntimeException("WS broker down"))
                .when(messagingTemplate)
                .convertAndSend(anyString(), any(Object.class));

        // Must not propagate — WS failure cannot break the execution flow
        publisher.publishExecutionUpdate(1L, 1L, "FAILED", "test-job");
    }

    @Test
    void publishMetrics_sendsToMetricsTopic() {
        Map<String, Object> metrics = Map.of("jobsFired", 100.0, "kafkaEventsSent", 500.0);

        publisher.publishMetrics(metrics);

        verify(messagingTemplate).convertAndSend("/topic/metrics", metrics);
    }
}
