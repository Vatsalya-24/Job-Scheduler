package com.jobScheduling.job.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka topic configuration — Render-safe version.
 *
 * CHANGES FOR RENDER / UPSTASH KAFKA:
 *
 * 1. Partitions reduced from 50 → configurable via KAFKA_PARTITIONS env var
 *    Upstash free tier: max 10 partitions total across all topics.
 *    Default here is 3 (safe for free tier).
 *    Set KAFKA_PARTITIONS=10 on Upstash paid tier.
 *    Set KAFKA_PARTITIONS=50 on self-hosted/dedicated Kafka.
 *
 * 2. Replication factor: Upstash manages replication internally.
 *    Set to 1 here — Upstash ignores this and applies its own RF.
 *
 * 3. snappy compression: Upstash supports snappy. Kept as-is.
 *
 * 4. Topic auto-creation: if KAFKA_AUTO_CREATE_TOPICS=false (Upstash default),
 *    these NewTopic beans attempt creation via AdminClient on startup.
 *    Upstash allows this — topics are created if they don't exist.
 */
@Configuration
public class KafkaTopicConfig {

    @Value("${kafka.partitions:3}")
    private int partitions;

    @Value("${kafka.topics.job-created}")
    private String jobCreatedTopic;

    @Value("${kafka.topics.job-execution-request}")
    private String jobExecutionRequestTopic;

    @Value("${kafka.topics.job-execution-result}")
    private String jobExecutionResultTopic;

    @Value("${kafka.topics.job-retry}")
    private String jobRetryTopic;

    @Value("${kafka.topics.dead-letter}")
    private String deadLetterTopic;

    @Bean
    public NewTopic jobCreatedTopic() {
        return TopicBuilder.name(jobCreatedTopic)
                .partitions(partitions)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(7 * 24 * 60 * 60 * 1000L))
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "snappy")
                .build();
    }

    @Bean
    public NewTopic jobExecutionRequestTopic() {
        return TopicBuilder.name(jobExecutionRequestTopic)
                .partitions(partitions)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(3 * 24 * 60 * 60 * 1000L))
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "snappy")
                .build();
    }

    @Bean
    public NewTopic jobExecutionResultTopic() {
        return TopicBuilder.name(jobExecutionResultTopic)
                .partitions(partitions)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(3 * 24 * 60 * 60 * 1000L))
                .build();
    }

    @Bean
    public NewTopic jobRetryTopic() {
        return TopicBuilder.name(jobRetryTopic)
                .partitions(Math.min(partitions, 3))
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(24 * 60 * 60 * 1000L))
                .build();
    }

    @Bean
    public NewTopic deadLetterTopic() {
        return TopicBuilder.name(deadLetterTopic)
                .partitions(Math.min(partitions, 2))
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(30L * 24 * 60 * 60 * 1000L))
                .build();
    }
}