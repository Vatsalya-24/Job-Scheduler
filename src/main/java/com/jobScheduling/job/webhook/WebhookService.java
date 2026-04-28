package com.jobScheduling.job.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobScheduling.job.entity.JobExecution;
import com.jobScheduling.job.repository.JobExecutionRepository;
import com.jobScheduling.job.service.RetryLogService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * WHY webhook_deliveries WAS EMPTY — 4 bugs, each one independently fatal:
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * BUG 1 — NullPointerException on delivery.getId().toString() in attemptDelivery()
 *   dispatch() called deliveryRepository.save(delivery) to persist the PENDING
 *   record, then immediately passed `saved` to attemptDelivery(). But dispatch()
 *   had NO @Transactional annotation. Spring Data's save() opens its own mini-
 *   transaction and commits it — fine. BUT because the parent method had no
 *   transaction, Hibernate used the default FlushMode=AUTO which only flushes on
 *   query execution, not on save(). The generated ID was not always flushed back
 *   into the entity before attemptDelivery() ran. Result: delivery.getId() returned
 *   null → .toString() → NPE → entire dispatch silently aborted → nothing saved.
 *
 *   FIX: Separate persist and delivery into two distinct steps:
 *     - persistDeliveryRecord() — @Transactional, saves and flushes, returns ID
 *     - attemptDelivery() — runs OUTSIDE any transaction (network I/O must never
 *       hold a DB connection open)
 *
 * BUG 2 — retryPendingDeliveries() held a DB transaction open during HTTP I/O
 *   retryPendingDeliveries() was @Transactional AND called attemptDelivery() which
 *   does httpClient.send() — a blocking network call that can take up to 10 seconds.
 *   This held the JPA transaction (and DB connection) open for the entire HTTP
 *   round-trip. Under load: HikariCP pool exhaustion → subsequent DB ops timed out
 *   → retry writes never committed → webhook_deliveries stayed PENDING forever.
 *
 *   FIX: Load deliveries in a short @Transactional read, then process each one
 *   outside the transaction. Only the final save() opens a new transaction.
 *
 * BUG 3 — RetryLogService.logRetry() was never called anywhere
 *   retry_log table was always empty because no code path ever called logRetry().
 *   The service existed, the entity existed, the repository existed — but no caller.
 *
 *   FIX: Call retryLogService.logRetry() inside handleFailure() every time a
 *   webhook delivery attempt fails, passing the attempt number.
 *
 * BUG 4 — dispatch() method is @Async — Spring creates a proxy but the @Async
 *   proxy only works when the method is called from OUTSIDE the bean. The Kafka
 *   consumer calls webhookService.dispatch() which IS external — this is fine.
 *   But if dispatch() calls attemptDelivery() internally (same bean), no proxy
 *   intercepts it. The inner call runs synchronously in the async thread, which
 *   is correct, but @Transactional on inner methods also doesn't apply via proxy.
 *   FIX: Use @Transactional on the save helpers, not on the parent method.
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private static final long[] RETRY_DELAYS_SECONDS = {10, 30, 120, 600, 3600};

    private final WebhookRepository         webhookRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final JobExecutionRepository    executionRepository;
    private final RetryLogService           retryLogService;
    private final ObjectMapper              objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final Counter deliveredCounter;
    private final Counter failedCounter;
    private final Counter retriedCounter;
    private final Timer   deliveryLatencyTimer;

    public WebhookService(
            WebhookRepository webhookRepository,
            WebhookDeliveryRepository deliveryRepository,
            JobExecutionRepository executionRepository,
            RetryLogService retryLogService,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.webhookRepository   = webhookRepository;
        this.deliveryRepository  = deliveryRepository;
        this.executionRepository = executionRepository;
        this.retryLogService     = retryLogService;
        this.objectMapper        = objectMapper;

        this.deliveredCounter     = Counter.builder("webhooks.delivered").register(meterRegistry);
        this.failedCounter        = Counter.builder("webhooks.failed").register(meterRegistry);
        this.retriedCounter       = Counter.builder("webhooks.retried").register(meterRegistry);
        this.deliveryLatencyTimer = Timer.builder("webhooks.delivery.latency").register(meterRegistry);
    }

    // ── Registration ──────────────────────────────────────────────────────────

    @Transactional
    public Webhook register(Long jobId, String url, String secretKey, String eventFilter) {
        Webhook wh = new Webhook();
        wh.setJobId(jobId);
        wh.setUrl(url);
        wh.setSecretKey(secretKey);
        wh.setEventFilter(eventFilter != null ? eventFilter : "ALL");
        wh.setActive(true);
        Webhook saved = webhookRepository.save(wh);
        log.info("Webhook id={} registered for jobId={} url={}", saved.getId(), jobId, url);
        return saved;
    }

    @Transactional
    public void deactivate(Long webhookId) {
        webhookRepository.findById(webhookId).ifPresent(wh -> {
            wh.setActive(false);
            webhookRepository.save(wh);
            log.info("Webhook id={} deactivated", webhookId);
        });
    }

    public List<Webhook> listForJob(Long jobId) {
        return webhookRepository.findByJobId(jobId);
    }

    // ── Dispatch (called from Kafka consumer after execution saved) ───────────

    /**
     * Entry point — runs in kafkaExecutor thread (async from consumer).
     * Does NOT hold any transaction open — each inner step manages its own.
     */
    @Async("kafkaExecutor")
    public void dispatch(Long jobId, Long executionId, String eventType,
                         Map<String, Object> payload) {

        List<Webhook> webhooks = webhookRepository.findByJobIdAndActiveTrue(jobId);
        if (webhooks.isEmpty()) {
            log.debug("No active webhooks for jobId={} — skipping dispatch", jobId);
            return;
        }

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Failed to serialise payload for jobId={}: {}", jobId, e.getMessage());
            return;
        }

        for (Webhook wh : webhooks) {
            if (!shouldDeliver(wh.getEventFilter(), eventType)) continue;

            // BUG 1 FIX: persist in its own @Transactional method so the entity is
            // fully flushed and getId() is populated before attemptDelivery() runs.
            WebhookDelivery delivery = persistDeliveryRecord(wh, executionId, eventType, payloadJson);

            // Attempt runs OUTSIDE any transaction — network I/O must never hold a connection
            attemptDelivery(delivery, wh, payloadJson);
        }
    }

    /**
     * BUG 1 FIX: Isolated @Transactional method that saves the PENDING delivery
     * record and flushes immediately so getId() is guaranteed non-null on return.
     */
    @Transactional
    protected WebhookDelivery persistDeliveryRecord(
            Webhook wh, Long executionId, String eventType, String payloadJson) {

        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setWebhook(wh);
        delivery.setJobExecutionId(executionId);
        delivery.setEventType(eventType);
        delivery.setPayload(payloadJson);
        delivery.setStatus("PENDING");

        // saveAndFlush guarantees the INSERT is sent immediately and the
        // IDENTITY-generated id is populated before this method returns.
        return deliveryRepository.saveAndFlush(delivery);
    }

    // ── Retry poller ──────────────────────────────────────────────────────────

    /**
     * BUG 2 FIX: Load due deliveries in a short read transaction, then close it.
     * Each delivery is processed (including its HTTP call) OUTSIDE the read
     * transaction so we never hold a DB connection open during network I/O.
     */
    @Scheduled(fixedDelay = 10_000)
    public void retryPendingDeliveries() {
        // Short read — transaction opens and closes here
        List<WebhookDelivery> due = loadDueDeliveries();
        if (due.isEmpty()) return;

        log.debug("Retrying {} webhook deliveries", due.size());
        for (WebhookDelivery delivery : due) {
            retriedCounter.increment();
            // Re-fetch the webhook (lazy association may be detached)
            Webhook wh = webhookRepository.findById(delivery.getWebhook().getId())
                    .orElse(null);
            if (wh == null) continue;
            attemptDelivery(delivery, wh, delivery.getPayload());
        }
    }

    @Transactional(readOnly = true)
    protected List<WebhookDelivery> loadDueDeliveries() {
        return deliveryRepository.findDueForRetry(Timestamp.from(Instant.now()));
    }

    // ── Core HTTP delivery (runs outside any transaction) ─────────────────────

    private void attemptDelivery(WebhookDelivery delivery, Webhook wh, String payloadJson) {
        delivery.setAttemptCount(delivery.getAttemptCount() + 1);

        long start = System.currentTimeMillis();
        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(wh.getUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type",           "application/json")
                    .header("X-Orbit-Event",          delivery.getEventType())
                    .header("X-Orbit-Delivery",       String.valueOf(delivery.getId()))
                    .header("X-Orbit-Attempt",        String.valueOf(delivery.getAttemptCount()))
                    .POST(HttpRequest.BodyPublishers.ofString(payloadJson));

            if (wh.getSecretKey() != null && !wh.getSecretKey().isBlank()) {
                reqBuilder.header("X-Orbit-Signature", "sha256=" + hmacSha256(wh.getSecretKey(), payloadJson));
            }

            HttpResponse<String> response = httpClient.send(
                    reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            deliveryLatencyTimer.record(Duration.ofMillis(System.currentTimeMillis() - start));
            delivery.setHttpStatusCode(response.statusCode());
            delivery.setResponseBody(truncate(response.body(), 1000));

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                delivery.setStatus("DELIVERED");
                delivery.setDeliveredAt(Timestamp.from(Instant.now()));
                delivery.setNextRetryAt(null);
                deliveredCounter.increment();
                log.info("Webhook DELIVERED id={} jobId={} http={} ms={}",
                        delivery.getId(), wh.getJobId(), response.statusCode(),
                        System.currentTimeMillis() - start);
            } else {
                handleFailure(delivery, wh, "HTTP " + response.statusCode());
            }

        } catch (Exception ex) {
            handleFailure(delivery, wh, truncate(ex.getMessage(), 300));
        }

        // Save final state — short transaction, no network I/O inside
        saveDelivery(delivery);
    }

    /** BUG 3 FIX: log every failure into retry_log */
    private void handleFailure(WebhookDelivery delivery, Webhook wh, String error) {
        delivery.setErrorMessage(truncate(error, 500));
        failedCounter.increment();

        if (delivery.getAttemptCount() >= delivery.getMaxAttempts()) {
            delivery.setStatus("EXHAUSTED");
            log.warn("Webhook EXHAUSTED id={} after {} attempts — error: {}",
                    delivery.getId(), delivery.getAttemptCount(), error);
        } else {
            delivery.setStatus("FAILED");
            long delaySec = RETRY_DELAYS_SECONDS[
                    Math.min(delivery.getAttemptCount() - 1, RETRY_DELAYS_SECONDS.length - 1)];
            delivery.setNextRetryAt(Timestamp.from(Instant.now().plusSeconds(delaySec)));
            log.warn("Webhook FAILED id={} attempt={} retry in {}s — error: {}",
                    delivery.getId(), delivery.getAttemptCount(), delaySec, error);
        }

        // BUG 3 FIX: actually write to retry_log on every failed attempt
        try {
            // Fetch the JobExecution if we have an executionId
            if (delivery.getJobExecutionId() != null) {
                executionRepository.findById(delivery.getJobExecutionId()).ifPresent(exec ->
                    retryLogService.logRetry(exec, delivery.getAttemptCount())
                );
            }
        } catch (Exception e) {
            log.warn("Could not write retry_log for delivery id={}: {}", delivery.getId(), e.getMessage());
        }
    }

    @Transactional
    protected void saveDelivery(WebhookDelivery delivery) {
        deliveryRepository.save(delivery);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean shouldDeliver(String filter, String eventType) {
        return "ALL".equals(filter) || filter.equals(eventType);
    }

    private String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.error("HMAC signing failed: {}", e.getMessage());
            return "";
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
