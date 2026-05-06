package com.jobScheduling.job;

import com.jobScheduling.job.config.IdempotencyFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyFilterTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private IdempotencyFilter filter;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        filter = new IdempotencyFilter(redis);
    }

    @Test
    void get_requests_skip_idempotency_check() throws Exception {
        MockHttpServletRequest  req   = new MockHttpServletRequest("GET", "/api/v1/jobs");
        MockHttpServletResponse res   = new MockHttpServletResponse();
        MockFilterChain         chain = new MockFilterChain();

        req.addHeader("Idempotency-Key", "some-key");
        filter.doFilter(req, res, chain);

        // No Redis interaction — GET passes straight through
        verifyNoInteractions(redis);
    }

    @Test
    void post_without_idempotency_key_passes_through() throws Exception {
        MockHttpServletRequest  req   = new MockHttpServletRequest("POST", "/api/v1/jobs");
        MockHttpServletResponse res   = new MockHttpServletResponse();
        MockFilterChain         chain = new MockFilterChain();

        filter.doFilter(req, res, chain);
        verifyNoInteractions(redis);
    }

    @Test
    void cache_miss_processes_request_normally() throws Exception {
        when(valueOps.get("idempotency:key-abc")).thenReturn(null);

        MockHttpServletRequest  req   = new MockHttpServletRequest("POST", "/api/v1/jobs");
        MockHttpServletResponse res   = new MockHttpServletResponse();
        req.addHeader("Idempotency-Key", "key-abc");
        req.setContent("{\"name\":\"job\"}".getBytes());

        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        // Should have checked Redis once
        verify(valueOps).get("idempotency:key-abc");
    }

    @Test
    void cache_hit_returns_cached_response_without_calling_chain() throws Exception {
        // Simulate a cached 201 response
        when(valueOps.get("idempotency:key-xyz")).thenReturn("201|{\"id\":42,\"name\":\"test\"}");

        MockHttpServletRequest  req   = new MockHttpServletRequest("POST", "/api/v1/jobs");
        MockHttpServletResponse res   = new MockHttpServletResponse();
        req.addHeader("Idempotency-Key", "key-xyz");

        // Chain should NOT be called — response comes from cache
        MockFilterChain chain = mock(MockFilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(201);
        assertThat(res.getContentAsString()).contains("\"id\":42");
        assertThat(res.getHeader("X-Idempotency-Replayed")).isEqualTo("true");
        verify(chain, never()).doFilter(any(), any());
    }
}
