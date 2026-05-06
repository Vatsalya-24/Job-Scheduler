package com.jobScheduling.job.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.time.Duration;

/**
 * Idempotency Filter — prevents duplicate mutations on client retry.
 *
 * How it works:
 *   Client includes header:  Idempotency-Key: <uuid>
 *   First request:  processes normally, caches response body + status in Redis (24hr TTL)
 *   Retry request:  returns the cached response immediately — DB never touched again
 *
 * Only applies to POST and PUT methods (state-changing requests).
 * GET, DELETE, PATCH are skipped.
 *
 * Resume talking point:
 *   "Implemented an idempotency layer using Redis so clients can safely retry
 *    failed requests without risk of duplicate job creation."
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final String HEADER = "Idempotency-Key";
    private static final String CACHE_PREFIX = "idempotency:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;

    public IdempotencyFilter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String method = request.getMethod();
        String idempotencyKey = request.getHeader(HEADER);

        // Only intercept POST / PUT with an idempotency key
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || (!method.equals("POST") && !method.equals("PUT"))) {
            chain.doFilter(request, response);
            return;
        }

        String cacheKey = CACHE_PREFIX + idempotencyKey;

        // ── Check cache ───────────────────────────────────────────────────────
        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            // Parse status|body format
            int sep = cached.indexOf('|');
            int status = Integer.parseInt(cached.substring(0, sep));
            String body = cached.substring(sep + 1);

            log.debug("Idempotency cache HIT for key={}", idempotencyKey);
            response.setStatus(status);
            response.setContentType("application/json");
            response.setHeader("X-Idempotency-Replayed", "true");
            response.getWriter().write(body);
            return;
        }

        // ── Process request ───────────────────────────────────────────────────
        ContentCachingRequestWrapper  wrappedReq = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper wrappedRes = new ContentCachingResponseWrapper(response);

        chain.doFilter(wrappedReq, wrappedRes);

        // ── Cache successful responses ────────────────────────────────────────
        int status = wrappedRes.getStatus();
        if (status >= 200 && status < 300) {
            String responseBody = new String(wrappedRes.getContentAsByteArray());
            String toCache = status + "|" + responseBody;
            redis.opsForValue().set(cacheKey, toCache, TTL);
            log.debug("Idempotency cache SET for key={} status={}", idempotencyKey, status);
        }

        wrappedRes.copyBodyToResponse();
    }
}
