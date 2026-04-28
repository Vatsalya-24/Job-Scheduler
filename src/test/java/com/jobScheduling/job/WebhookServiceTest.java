package com.jobScheduling.job;

import com.jobScheduling.job.entity.JobExecution;
import com.jobScheduling.job.repository.JobExecutionRepository;
import com.jobScheduling.job.service.RetryLogService;
import com.jobScheduling.job.webhook.Webhook;
import com.jobScheduling.job.webhook.WebhookDelivery;
import com.jobScheduling.job.webhook.WebhookDeliveryRepository;
import com.jobScheduling.job.webhook.WebhookRepository;
import com.jobScheduling.job.webhook.WebhookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    @Mock private WebhookRepository         webhookRepository;
    @Mock private WebhookDeliveryRepository deliveryRepository;
    @Mock private JobExecutionRepository    executionRepository;
    @Mock private RetryLogService           retryLogService;

    private WebhookService service;

    @BeforeEach
    void setUp() {
        service = new WebhookService(
                webhookRepository,
                deliveryRepository,
                executionRepository,
                retryLogService,
                new ObjectMapper(),
                new SimpleMeterRegistry()
        );
    }

    @Test
    void register_savesWebhookWithCorrectFields() {
        Webhook saved = webhookWithId(1L, 10L);
        when(webhookRepository.save(any())).thenReturn(saved);

        Webhook result = service.register(10L, "https://example.com/hook", "secret123", "SUCCESS");

        verify(webhookRepository).save(argThat(wh ->
                wh.getJobId().equals(10L) &&
                wh.getUrl().equals("https://example.com/hook") &&
                wh.getSecretKey().equals("secret123") &&
                wh.getEventFilter().equals("SUCCESS") &&
                wh.isActive()
        ));
    }

    @Test
    void deactivate_setsActiveFalse() {
        Webhook wh = new Webhook();
        wh.setJobId(1L); wh.setUrl("https://test.com"); wh.setActive(true);
        when(webhookRepository.findById(42L)).thenReturn(Optional.of(wh));

        service.deactivate(42L);

        assertThat(wh.isActive()).isFalse();
        verify(webhookRepository).save(wh);
    }

    @Test
    void dispatch_skipsWhenNoWebhooksRegistered() {
        when(webhookRepository.findByJobIdAndActiveTrue(99L)).thenReturn(List.of());

        service.dispatch(99L, 1L, "SUCCESS", Map.of("jobId", 99L));

        verifyNoInteractions(deliveryRepository);
    }

    @Test
    void dispatch_skipsWhenEventFilterDoesNotMatch() {
        Webhook wh = new Webhook();
        wh.setJobId(1L); wh.setUrl("https://example.com");
        wh.setEventFilter("FAILED"); wh.setActive(true);

        when(webhookRepository.findByJobIdAndActiveTrue(1L)).thenReturn(List.of(wh));

        // Dispatch a SUCCESS event — webhook only wants FAILED
        service.dispatch(1L, 1L, "SUCCESS", Map.of("jobId", 1L));

        verifyNoInteractions(deliveryRepository);
    }

    @Test
    void listForJob_returnsAllWebhooksForJob() {
        Webhook wh1 = new Webhook(); wh1.setJobId(5L);
        Webhook wh2 = new Webhook(); wh2.setJobId(5L);
        when(webhookRepository.findByJobId(5L)).thenReturn(List.of(wh1, wh2));

        List<Webhook> result = service.listForJob(5L);

        assertThat(result).hasSize(2);
    }

    // ── BUG 3 FIX test: retryLogService.logRetry() is called on failure ───────

    @Test
    void handleFailure_writesToRetryLog_whenExecutionExists() {
        // Arrange: create a delivery that references an execution
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setJobExecutionId(7L);
        delivery.setAttemptCount(1);
        delivery.setAttemptCount(5);

        JobExecution exec = new JobExecution();
        when(executionRepository.findById(7L)).thenReturn(Optional.of(exec));
        when(deliveryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Webhook wh = new Webhook();
        wh.setJobId(1L);
        wh.setUrl("https://example.com");

        // Calling persistDeliveryRecord sets up a saved delivery — mock it
        WebhookDelivery savedDelivery = new WebhookDelivery();
        try { var f = WebhookDelivery.class.getDeclaredField("id"); f.setAccessible(true); f.set(savedDelivery, 1L); } catch (Exception ignored) {}
        savedDelivery.setWebhook(wh);
        savedDelivery.setJobExecutionId(7L);
        savedDelivery.setAttemptCount(0);
        savedDelivery.setMaxAttempts(5);
        savedDelivery.setEventType("SUCCESS");
        savedDelivery.setPayload("{\"jobId\":1}");
        when(deliveryRepository.saveAndFlush(any())).thenReturn(savedDelivery);

        // dispatch() with an unreachable URL will fail and call handleFailure()
        Webhook triggerWh = new Webhook();
        triggerWh.setJobId(1L);
        triggerWh.setUrl("http://localhost:1/unreachable");
        triggerWh.setEventFilter("ALL");
        triggerWh.setActive(true);

        when(webhookRepository.findByJobIdAndActiveTrue(1L)).thenReturn(List.of(triggerWh));
        when(executionRepository.findById(7L)).thenReturn(Optional.of(exec));

        service.dispatch(1L, 7L, "SUCCESS", Map.of("jobId", 1L));

        // Wait for async execution (kafkaExecutor runs in same thread in test due to @Async proxy)
        // In unit tests @Async is NOT applied (no Spring context), so the call is synchronous
        verify(retryLogService, atLeastOnce()).logRetry(eq(exec), anyInt());
    }

    private Webhook webhookWithId(Long id, Long jobId) {
        Webhook wh = new Webhook();
        try {
            var f = Webhook.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(wh, id);
        } catch (Exception ignored) {}
        wh.setJobId(jobId);
        wh.setUrl("https://example.com/hook");
        wh.setActive(true);
        return wh;
    }
}
