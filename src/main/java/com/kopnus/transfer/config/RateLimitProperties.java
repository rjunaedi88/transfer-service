package com.kopnus.transfer.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(@NotNull @Valid Limit transfer, @NotNull @Valid Limit login) {

    public record Limit(@Positive long capacity, @NotNull Duration period) {
    }
}
