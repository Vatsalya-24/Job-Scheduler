package com.jobScheduling.job.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    // ── Partition count: scale to match consumer concurrency ──────────────────
    // Rule of thumb: partitions >= consumer threads across all instances
    // For 50k rps across 5 instances × 10 threads = 50 partitions minimum
    private static final int PARTITIONS = 50;
    private static final int REPLICATION_FACTOR = 1;

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
                .partitions(PARTITIONS)
                .replicas(REPLICATION_FACTOR)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(7 * 24 * 60 * 60 * 1000L)) // 7 days
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "snappy")
                .build();
    }

    @Bean
    public NewTopic jobExecutionRequestTopic() {
        return TopicBuilder.name(jobExecutionRequestTopic)
                .partitions(PARTITIONS)
                .replicas(REPLICATION_FACTOR)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(3 * 24 * 60 * 60 * 1000L))
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "snappy")
                .build();
    }

    @Bean
    public NewTopic jobExecutionResultTopic() {
        return TopicBuilder.name(jobExecutionResultTopic)
                .partitions(PARTITIONS)
                .replicas(REPLICATION_FACTOR)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(3 * 24 * 60 * 60 * 1000L))
                .build();
    }

    @Bean
    public NewTopic jobRetryTopic() {
        return TopicBuilder.name(jobRetryTopic)
                .partitions(10)
                .replicas(REPLICATION_FACTOR)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(24 * 60 * 60 * 1000L))
                .build();
    }

    @Bean
    public NewTopic deadLetterTopic() {
        return TopicBuilder.name(deadLetterTopic)
                .partitions(5)
                .replicas(REPLICATION_FACTOR)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(30L * 24 * 60 * 60 * 1000L)) // 30 days
                .build();
    }
}
