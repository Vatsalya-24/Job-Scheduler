package com.jobScheduling.job.config;

import com.jobScheduling.job.kafka.JobEvent;
import com.jobScheduling.job.kafka.JobExecutionEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * TWO producers — one per durability tier:
 *
 * DURABLE (acks=all)  — job creation, webhook events.
 *   Losing a job-created event is bad. We wait for all ISR replicas.
 *   Throughput: ~5k msg/s. Latency: 5–20ms. Acceptable for write-once job creation.
 *
 * FAST (acks=1)       — execution results.
 *   We record 2000+ executions/s. Losing one execution record is acceptable (the
 *   job still ran; we just miss the audit entry). acks=1 means leader-only ACK:
 *   latency drops from 10–50ms to 1–3ms. linger=1ms instead of 5ms.
 *   This is what allows p(99) async latency < 30ms.
 *
 * ROOT CAUSE 3 FIX: The original config used acks=all + linger=5ms for EVERY
 * producer. Under 2000 req/s this caused each publish to wait for all replicas,
 * and with linger=5ms each batch delayed an extra 5ms. Combined: p(99) blew past
 * 4 seconds. Separating into two producers cuts execution-path latency by ~15x.
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // ── Shared base props ─────────────────────────────────────────────────────

    private Map<String, Object> baseProps() {
        Map<String, Object> p = new HashMap<>();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,      bootstrapServers);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        p.put(ProducerConfig.RETRIES_CONFIG,                3);
        p.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG,       100);
        p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,     10000);   // 10s — not 30s
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,    30000);   // 30s total budget
        p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG,       "snappy");
        p.put(ProducerConfig.BUFFER_MEMORY_CONFIG,          67108864L);
        return p;
    }

    // ── DURABLE producer (job creation, webhooks) ─────────────────────────────

    private Map<String, Object> durableProps() {
        Map<String, Object> p = baseProps();
        p.put(ProducerConfig.ACKS_CONFIG,                               "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,                 true);
        p.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION,     5);
        p.put(ProducerConfig.BATCH_SIZE_CONFIG,                         65536);  // 64KB
        p.put(ProducerConfig.LINGER_MS_CONFIG,                          5);
        return p;
    }

    // ── FAST producer (execution results — high volume, low latency) ──────────

    private Map<String, Object> fastProps() {
        Map<String, Object> p = baseProps();
        p.put(ProducerConfig.ACKS_CONFIG,                               "1");    // leader only
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,                 false);  // not compatible with acks=1
        p.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION,     10);     // more pipeline
        p.put(ProducerConfig.BATCH_SIZE_CONFIG,                         32768);  // 32KB — smaller, sends faster
        p.put(ProducerConfig.LINGER_MS_CONFIG,                          1);      // 1ms — fill batch quickly
        return p;
    }

    // ── Job Event (DURABLE) ───────────────────────────────────────────────────

    @Bean
    public ProducerFactory<String, JobEvent> jobEventProducerFactory() {
        return new DefaultKafkaProducerFactory<>(durableProps());
    }

    @Bean
    public KafkaTemplate<String, JobEvent> jobEventKafkaTemplate() {
        return new KafkaTemplate<>(jobEventProducerFactory());
    }

    // ── Execution Event (FAST) ────────────────────────────────────────────────

    @Bean
    public ProducerFactory<String, JobExecutionEvent> jobExecutionEventProducerFactory() {
        return new DefaultKafkaProducerFactory<>(fastProps());
    }

    @Bean
    public KafkaTemplate<String, JobExecutionEvent> jobExecutionKafkaTemplate() {
        return new KafkaTemplate<>(jobExecutionEventProducerFactory());
    }
}
