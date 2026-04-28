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

@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${kafka.consumer.concurrency:10}")
    private int concurrency;

    private Map<String, Object> baseConsumerProps(String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);

        // Never lose a message — manual commit
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // Throughput tuning
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1);
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);

        // Session / heartbeat — avoid false rebalances
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);

        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.jobScheduling.job.*");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        return props;
    }

    // ── Job Event Consumer ────────────────────────────────────────────────────

    @Bean
    public ConsumerFactory<String, JobEvent> jobEventConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(
                baseConsumerProps("job-scheduler-group"),
                new StringDeserializer(),
                new JsonDeserializer<>(JobEvent.class, false)
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, JobEvent> jobEventListenerFactory(
            KafkaTemplate<String, JobEvent> kafkaTemplate) {

        ConcurrentKafkaListenerContainerFactory<String, JobEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(jobEventConsumerFactory());

        // High concurrency — 10 threads per instance = handles bursts
        factory.setConcurrency(concurrency);

        // Manual acknowledgement — commit only on successful processing
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        // Error handler: retry 3 times with 1s backoff, then send to DLT
        factory.setCommonErrorHandler(buildErrorHandler(kafkaTemplate));

        // Batch listener for high throughput
        factory.setBatchListener(true);

        return factory;
    }

    // ── Job Execution Event Consumer ─────────────────────────────────────────

    @Bean
    public ConsumerFactory<String, JobExecutionEvent> jobExecutionEventConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(
                baseConsumerProps("job-execution-group"),
                new StringDeserializer(),
                new JsonDeserializer<>(JobExecutionEvent.class, false)
        );
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

    // ── Shared Error Handler ──────────────────────────────────────────────────

    private <V> DefaultErrorHandler buildErrorHandler(KafkaTemplate<String, V> kafkaTemplate) {
        // Publish failed records to DLT after exhausting retries
        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(kafkaTemplate);

        // 3 retries with 1-second fixed backoff before DLT
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer, new FixedBackOff(1000L, 3L));

        // Don't retry on deserialization errors — they'll always fail
        errorHandler.addNotRetryableExceptions(
                org.springframework.kafka.support.serializer.DeserializationException.class
        );

        return errorHandler;
    }
}
