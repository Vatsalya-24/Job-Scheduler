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
 * Two-tier Kafka producer config — Render/Upstash compatible.
 *
 * CHANGES FOR RENDER:
 *   - SASL/SSL properties injected from env vars (Upstash requires SASL_SSL)
 *   - idempotence disabled on fast producer (requires acks=all; Upstash free
 *     tier doesn't guarantee this without increased latency)
 *   - Buffer memory reduced from 64MB to 16MB — fits in Render's 512MB RAM
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // Upstash SASL config — empty strings when not on Upstash (local/dev)
    @Value("${spring.kafka.properties.security.protocol:PLAINTEXT}")
    private String securityProtocol;

    @Value("${spring.kafka.properties.sasl.mechanism:PLAIN}")
    private String saslMechanism;

    @Value("${spring.kafka.properties.sasl.jaas.config:}")
    private String saslJaasConfig;

    private Map<String, Object> baseProps() {
        Map<String, Object> p = new HashMap<>();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        p.put(ProducerConfig.RETRIES_CONFIG, 3);
        p.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 200);
        p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 15000);
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 60000);
        p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        p.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 16777216L); // 16MB (reduced for Render)

        // Upstash SASL — only applied if security.protocol != PLAINTEXT
        if (!"PLAINTEXT".equals(securityProtocol)) {
            p.put("security.protocol", securityProtocol);
            p.put("sasl.mechanism", saslMechanism);
            if (!saslJaasConfig.isBlank()) {
                p.put("sasl.jaas.config", saslJaasConfig);
            }
        }
        return p;
    }

    // DURABLE: for job creation (acks=all, wait for leader + replicas)
    private Map<String, Object> durableProps() {
        Map<String, Object> p = baseProps();
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false); // not needed for our use case
        p.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        p.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        p.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        return p;
    }

    // FAST: for execution results (acks=1, low latency)
    private Map<String, Object> fastProps() {
        Map<String, Object> p = baseProps();
        p.put(ProducerConfig.ACKS_CONFIG, "1");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
        p.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 10);
        p.put(ProducerConfig.BATCH_SIZE_CONFIG, 8192);
        p.put(ProducerConfig.LINGER_MS_CONFIG, 1);
        return p;
    }

    @Bean
    public ProducerFactory<String, JobEvent> jobEventProducerFactory() {
        return new DefaultKafkaProducerFactory<>(durableProps());
    }

    @Bean
    public KafkaTemplate<String, JobEvent> jobEventKafkaTemplate() {
        return new KafkaTemplate<>(jobEventProducerFactory());
    }

    @Bean
    public ProducerFactory<String, JobExecutionEvent> jobExecutionEventProducerFactory() {
        return new DefaultKafkaProducerFactory<>(fastProps());
    }

    @Bean
    public KafkaTemplate<String, JobExecutionEvent> jobExecutionKafkaTemplate() {
        return new KafkaTemplate<>(jobExecutionEventProducerFactory());
    }
}