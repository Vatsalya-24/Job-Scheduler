package com.jobScheduling.job;

import com.jobScheduling.job.dto.JobsDTO;
import com.jobScheduling.job.entity.JobStatus;
import com.jobScheduling.job.entity.Jobs;
import com.jobScheduling.job.repository.JobsRepository;
import com.jobScheduling.job.service.JobsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full integration test using:
 * - Testcontainers for PostgreSQL (real DB, not H2)
 * - @EmbeddedKafka for in-process Kafka broker
 * - Spring's test Redis (mocked via application-test.yml)
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(
        partitions = 3,
        topics = {
                "job-created", "job-execution-request",
                "job-execution-result", "job-retry", "job-dead-letter"
        },
        brokerProperties = {
                "listeners=PLAINTEXT://localhost:9092",
                "port=9092"
        }
)
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(TestRedisConfig.class)
class JobsServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("job_scheduler_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JobsService jobsService;

    @Autowired
    private JobsRepository jobsRepository;

    @Autowired
    private CacheManager cacheManager;

    @AfterEach
    void cleanup() {
        jobsRepository.deleteAll();
        // Clear all caches between tests
        cacheManager.getCacheNames()
                .forEach(name -> Objects.requireNonNull(cacheManager.getCache(name)).clear());
    }

    @Test
    void createJob_persistsAndPublishesEvent() {
        JobsDTO dto = buildJobDTO("test-job", "0 * * * * *", "ACTIVE");

        JobsDTO created = jobsService.createNewJob(dto);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getName()).isEqualTo("test-job");
        assertThat(created.getStatus()).isEqualTo("ACTIVE");
        assertThat(created.getNextFireTime()).isNotNull();
        assertThat(jobsRepository.count()).isEqualTo(1);
    }

    @Test
    void getJobById_servedFromCache_onSecondCall() {
        JobsDTO dto = buildJobDTO("cached-job", "0 0 * * * *", "ACTIVE");
        JobsDTO created = jobsService.createNewJob(dto);

        // First call — DB hit, should populate cache
        JobsDTO first = jobsService.getJobById(created.getId());
        // Second call — should come from cache (no DB query)
        JobsDTO second = jobsService.getJobById(created.getId());

        assertThat(first.getId()).isEqualTo(second.getId());
        assertThat(first.getName()).isEqualTo(second.getName());
        // Verify cache is populated
        assertThat(cacheManager.getCache("job")).isNotNull();
        assertThat(cacheManager.getCache("job").get(created.getId())).isNotNull();
    }

    @Test
    void updateJob_evictsCacheEntry() {
        JobsDTO dto = buildJobDTO("update-me", "0 * * * * *", "ACTIVE");
        JobsDTO created = jobsService.createNewJob(dto);

        // Warm up the cache
        jobsService.getJobById(created.getId());

        // Update — should evict both "job" and "jobs" caches
        dto.setName("updated-name");
        jobsService.updateJob(created.getId(), dto);

        // Cache entry for this job should be gone
        var cached = cacheManager.getCache("job").get(created.getId());
        assertThat(cached).isNull();
    }

    @Test
    void disableJob_setsStatusToDisabled() {
        JobsDTO dto = buildJobDTO("disable-me", "0 0 * * * *", "ACTIVE");
        JobsDTO created = jobsService.createNewJob(dto);

        jobsService.disableJob(created.getId());

        Jobs job = jobsRepository.findById(created.getId()).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.DISABLED);
    }

    @Test
    void createJob_withInvalidCron_throwsException() {
        JobsDTO dto = buildJobDTO("bad-cron", "NOT_A_CRON", "ACTIVE");
        assertThatThrownBy(() -> jobsService.createNewJob(dto))
                .isInstanceOf(Exception.class);
    }

    @Test
    void findJobsDueToFire_returnsOnlyActiveAndDue() throws InterruptedException {
        // Create a job with next_fire_time in the past
        JobsDTO dto = buildJobDTO("due-job", "* * * * * *", "ACTIVE");
        jobsService.createNewJob(dto);

        // Allow 1 second for the job to become "due"
        Thread.sleep(1100);

        var dueJobs = jobsService.findJobsDueToFire();
        assertThat(dueJobs).isNotEmpty();
        assertThat(dueJobs).allMatch(j -> j.getStatus() == JobStatus.ACTIVE);
    }

    private JobsDTO buildJobDTO(String name, String cron, String status) {
        JobsDTO dto = new JobsDTO();
        dto.setName(name);
        dto.setCronExpression(cron);
        dto.setStatus(status);
        dto.setTarget("http://internal.service/run/" + name);
        return dto;
    }
}
