package com.kopnus.transfer.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.kopnus.transfer.config.RateLimitProperties;
import com.kopnus.transfer.exception.ErrorCode;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Token bucket in-memory: transfer dibatasi per nasabah, login dibatasi per IP
 * untuk menahan brute force. Bucket yang lama tidak dipakai dibuang oleh cache.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String TRANSFER_PATH = "/api/v1/transfers";

    private final RateLimitProperties properties;
    private final ErrorResponseWriter errorWriter;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1))
            .maximumSize(100_000)
            .build();

    public RateLimitFilter(RateLimitProperties properties, ErrorResponseWriter errorWriter) {
        this.properties = properties;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Bucket bucket = resolveBucket(request);
        if (bucket == null) {
            chain.doFilter(request, response);
            return;
        }

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        errorWriter.write(request, response, ErrorCode.RATE_LIMIT_EXCEEDED);
    }

    private Bucket resolveBucket(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return null;
        }
        String path = request.getRequestURI();
        if (LOGIN_PATH.equals(path)) {
            return bucketFor("login:" + request.getRemoteAddr(), properties.login());
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (TRANSFER_PATH.equals(path) && authentication instanceof JwtAuthenticationToken) {
            return bucketFor("transfer:" + authentication.getName(), properties.transfer());
        }
        return null;
    }

    private Bucket bucketFor(String key, RateLimitProperties.Limit limit) {
        return buckets.get(key, ignored -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(limit.capacity())
                        .refillGreedy(limit.capacity(), limit.period())
                        .build())
                .build());
    }
}
