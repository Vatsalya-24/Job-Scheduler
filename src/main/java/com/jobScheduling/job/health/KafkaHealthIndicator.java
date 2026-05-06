package com.jobScheduling.job.health;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Custom Kafka health indicator.
 * Exposed at GET /actuator/health — included in the "kafka" health group.
 */
@Component("kafka")
public class KafkaHealthIndicator implements HealthIndicator {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Override
    public Health health() {
        try (AdminClient client = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "3000",
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "3000"
        ))) {
            DescribeClusterResult cluster = client.describeCluster();
            int nodeCount = cluster.nodes().get(5, TimeUnit.SECONDS).size();
            String clusterId = cluster.clusterId().get(5, TimeUnit.SECONDS);

            return Health.up()
                    .withDetail("clusterId", clusterId)
                    .withDetail("nodeCount", nodeCount)
                    .withDetail("bootstrapServers", bootstrapServers)
                    .build();
        } catch (Exception ex) {
            return Health.down()
                    .withDetail("error", ex.getMessage())
                    .withDetail("bootstrapServers", bootstrapServers)
                    .build();
        }
    }
}
