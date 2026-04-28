package com.jobScheduling.job.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Production-safe Redis configuration
 *
 * Important:
 * DO NOT expose ObjectMapper as a Spring Bean.
 * If ObjectMapper is declared as @Bean with activateDefaultTyping(),
 * Spring MVC starts using it for REST APIs and causes:
 *
 * missing type id property '@class'
 *
 * for normal POST requests.
 *
 * Correct approach:
 * - Spring MVC uses default ObjectMapper (safe for REST)
 * - Redis uses dedicated local ObjectMapper (safe for cache)
 */
@Configuration
@EnableCaching
public class RedisConfig {

    /**
     * RedisTemplate for manual Redis operations
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory factory
    ) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // Dedicated Redis-only ObjectMapper
        ObjectMapper redisMapper = new ObjectMapper();
        redisMapper.registerModule(new JavaTimeModule());
        redisMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // IMPORTANT:
        // Enable type metadata ONLY for Redis cache
        // so DTOs deserialize correctly from Redis
        redisMapper.activateDefaultTyping(
                redisMapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(redisMapper);

        // Key serializers
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        // Value serializers
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * CacheManager for @Cacheable / @CacheEvict / @CachePut
     */
    @Bean
    public CacheManager cacheManager(
            RedisConnectionFactory factory
    ) {
        // Dedicated Redis-only ObjectMapper
        ObjectMapper redisMapper = new ObjectMapper();
        redisMapper.registerModule(new JavaTimeModule());
        redisMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // IMPORTANT:
        // Type info required ONLY for Redis cache deserialization
        redisMapper.activateDefaultTyping(
                redisMapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(redisMapper);

        // Default cache configuration
        RedisCacheConfiguration defaultConfig =
                RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(Duration.ofMinutes(5))
                        .serializeKeysWith(
                                RedisSerializationContext.SerializationPair
                                        .fromSerializer(new StringRedisSerializer())
                        )
                        .serializeValuesWith(
                                RedisSerializationContext.SerializationPair
                                        .fromSerializer(serializer)
                        )
                        .disableCachingNullValues();

        /*
         * Per-cache TTL customization
         */
        Map<String, RedisCacheConfiguration> caches = new HashMap<>();

        // Single job details
        caches.put(
                "job",
                defaultConfig.entryTtl(Duration.ofMinutes(5))
        );

        // Job list / pagination
        caches.put(
                "jobs",
                defaultConfig.entryTtl(Duration.ofMinutes(2))
        );

        // Execution history
        caches.put(
                "executions",
                defaultConfig.entryTtl(Duration.ofMinutes(10))
        );

        // Dashboard stats
        caches.put(
                "jobStats",
                defaultConfig.entryTtl(Duration.ofSeconds(10))
        );

        // Dashboard summary
        caches.put(
                "dashboardSummary",
                defaultConfig.entryTtl(Duration.ofSeconds(10))
        );

        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(caches)
                .transactionAware()
                .build();
    }
}