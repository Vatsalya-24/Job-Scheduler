package com.jobScheduling.job.config;

import com.jobScheduling.job.kafka.JobEvent;
import com.jobScheduling.job.kafka.JobExecutionEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer config — Render/Upstash compatible.
 *
 * CHANGES FOR RENDER:
 *   - SASL/SSL properties injected from env vars
 *   - Concurrency configurable via KAFKA_CONSUMER_CONCURRENCY (default 2)
 *     Upstash free tier: 3 partitions max, so 2 consumers is sufficient
 *   - max-poll-records reduced to 100 (less memory pressure on 512MB Render tier)
 */
@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${kafka.consumer.concurrency:2}")
    private int concurrency;

    @Value("${spring.kafka.properties.security.protocol:PLAINTEXT}")
    private String securityProtocol;

    @Value("${spring.kafka.properties.sasl.mechanism:PLAIN}")
    private String saslMechanism;

    @Value("${spring.kafka.properties.sasl.jaas.config:}")
    private String saslJaasConfig;

    private Map<String, Object> baseConsumerProps(String groupId) {
        Map<String, Object> p = new HashMap<>();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        p.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1);
        p.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);
        p.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 45000);
        p.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 15000);
        p.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);
        p.put(JsonDeserializer.TRUSTED_PACKAGES, "com.jobScheduling.job.*");
        p.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        // Upstash SASL
        if (!"PLAINTEXT".equals(securityProtocol)) {
            p.put("security.protocol", securityProtocol);
            p.put("sasl.mechanism", saslMechanism);
            if (!saslJaasConfig.isBlank()) {
                p.put("sasl.jaas.config", saslJaasConfig);
            }
        }
        return p;
    }

    @Bean
    public ConsumerFactory<String, JobEvent> jobEventConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(
                baseConsumerProps("orbit-group"),
                new StringDeserializer(),
                new JsonDeserializer<>(JobEvent.class, false));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, JobEvent> jobEventListenerFactory(
            KafkaTemplate<String, JobEvent> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<String, JobEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(jobEventConsumerFactory());
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(buildErrorHandler(kafkaTemplate));
        factory.setBatchListener(true);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, JobExecutionEvent> jobExecutionEventConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(
                baseConsumerProps("orbit-execution-group"),
                new StringDeserializer(),
                new JsonDeserializer<>(JobExecutionEvent.class, false));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, JobExecutionEvent> jobExecutionListenerFactory(
            KafkaTemplate<String, JobEvent> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<String, JobExecutionEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(jobExecutionEventConsumerFactory());
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(buildErrorHandler(kafkaTemplate));
        return factory;
    }

    private <V> DefaultErrorHandler buildErrorHandler(KafkaTemplate<String, V> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(kafkaTemplate);
        DefaultErrorHandler handler = new DefaultErrorHandler(
                recoverer, new FixedBackOff(2000L, 3L));
        handler.addNotRetryableExceptions(
                org.springframework.kafka.support.serializer.DeserializationException.class);
        return handler;
    }
}