package com.jobScheduling.job;

import com.jobScheduling.job.kafka.JobEvent;
import com.jobScheduling.job.kafka.KafkaProducerService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaProducerServiceTest {

    @Mock
    private KafkaTemplate<String, JobEvent> jobEventKafkaTemplate;

    @Mock
    private KafkaTemplate<String, com.jobScheduling.job.kafka.JobExecutionEvent> jobExecutionKafkaTemplate;

    private KafkaProducerService producerService;

    @BeforeEach
    void setUp() {
        producerService = new KafkaProducerService(
                jobEventKafkaTemplate,
                jobExecutionKafkaTemplate,
                new SimpleMeterRegistry()
        );
        // Inject topic names (normally injected via @Value)
        ReflectionTestUtils.setField(producerService, "jobCreatedTopic", "job-created");
        ReflectionTestUtils.setField(producerService, "jobExecutionResultTopic", "job-execution-result");
        ReflectionTestUtils.setField(producerService, "jobRetryTopic", "job-retry");
    }

    @Test
    void publishJobCreated_usesJobIdAsKey() {
        JobEvent event = new JobEvent(
                42L, "my-job", "0 * * * * *",
                "http://target", "ACTIVE", Instant.now());

        SendResult<String, JobEvent> mockResult = mockSendResult("job-created", 42L);
        when(jobEventKafkaTemplate.send(eq("job-created"), eq("42"), eq(event)))
                .thenReturn(CompletableFuture.completedFuture(mockResult));

        var future = producerService.publishJobCreated(event);

        assertThat(future).isCompletedWithValue(mockResult);
        verify(jobEventKafkaTemplate).send("job-created", "42", event);
    }

    @Test
    void publishJobCreated_logsErrorOnFailure() {
        JobEvent event = new JobEvent(
                99L, "fail-job", "0 * * * * *",
                "http://target", "ACTIVE", Instant.now());

        CompletableFuture<SendResult<String, JobEvent>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka unavailable"));

        when(jobEventKafkaTemplate.send(anyString(), anyString(), any(JobEvent.class)))
                .thenReturn(failedFuture);

        // Should not throw — failure is handled internally
        var result = producerService.publishJobCreated(event);
        assertThat(result).isCompletedExceptionally();
    }

    @SuppressWarnings("unchecked")
    private <V> SendResult<String, V> mockSendResult(String topic, long jobId) {
        ProducerRecord<String, V> record =
                new ProducerRecord<>(topic, String.valueOf(jobId), null);
        RecordMetadata meta =
                new RecordMetadata(new TopicPartition(topic, 0), 0, 0, 0, 0, 0);
        return new SendResult<>(record, meta);
    }
}
