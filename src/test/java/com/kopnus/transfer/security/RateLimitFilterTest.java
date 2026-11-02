package com.kopnus.transfer.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.kopnus.transfer.config.RateLimitProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

class RateLimitFilterTest {

    private static final String TRANSFER = "/api/v1/transfers";
    private static final String LOGIN = "/api/v1/auth/login";

    private final RateLimitFilter filter = new RateLimitFilter(
            new RateLimitProperties(
                    new RateLimitProperties.Limit(2, Duration.ofMinutes(1)),
                    new RateLimitProperties.Limit(1, Duration.ofMinutes(1))),
            new ErrorResponseWriter(JsonMapper.builder().findAndAddModules().build()));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String customerId) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256").subject(customerId).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private MockHttpServletResponse send(String method, String path, String remoteAddress)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remoteAddress);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return response;
    }

    private int transferStatus() throws ServletException, IOException {
        return send("POST", TRANSFER, "10.0.0.1").getStatus();
    }

    @Test
    void transferOverLimitGets429WithRetryAfter() throws Exception {
        authenticateAs("1");
        assertThat(transferStatus()).isEqualTo(200);
        assertThat(transferStatus()).isEqualTo(200);

        MockHttpServletResponse blocked = send("POST", TRANSFER, "10.0.0.1");

        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(Integer.parseInt(blocked.getHeader("Retry-After"))).isPositive();
        assertThat(blocked.getContentType()).startsWith("application/json");
        assertThat(blocked.getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
    }

    @Test
    void eachCustomerHasOwnBucket() throws Exception {
        authenticateAs("1");
        transferStatus();
        transferStatus();
        assertThat(transferStatus()).isEqualTo(429);

        authenticateAs("2");

        assertThat(transferStatus()).isEqualTo(200);
    }

    @Test
    void loginIsLimitedPerIp() throws Exception {
        assertThat(send("POST", LOGIN, "10.0.0.1").getStatus()).isEqualTo(200);
        assertThat(send("POST", LOGIN, "10.0.0.1").getStatus()).isEqualTo(429);
        assertThat(send("POST", LOGIN, "10.0.0.2").getStatus()).isEqualTo(200);
    }

    @Test
    void anonymousTransferAndOtherPathsAreNotCounted() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(transferStatus()).isEqualTo(200);
            assertThat(send("GET", "/actuator/health", "10.0.0.1").getStatus()).isEqualTo(200);
        }
    }
}
