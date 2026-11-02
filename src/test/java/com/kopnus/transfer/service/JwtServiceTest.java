package com.kopnus.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kopnus.transfer.config.JwtProperties;
import com.kopnus.transfer.config.SecurityConfig;
import com.kopnus.transfer.entity.Customer;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-with-at-least-32-characters";
    private static final Customer CUSTOMER = new Customer(7L, "budi", "hash");

    private final SecurityConfig securityConfig = new SecurityConfig();

    private JwtService serviceWith(JwtProperties properties) {
        return new JwtService(securityConfig.jwtEncoder(properties), properties);
    }

    private JwtDecoder decoderWith(JwtProperties properties) {
        return securityConfig.jwtDecoder(properties);
    }

    @Test
    void generatedTokenCanBeDecodedAndCarriesCustomerId() {
        JwtProperties properties = new JwtProperties(SECRET, "transfer-service", Duration.ofMinutes(15));

        Jwt jwt = decoderWith(properties).decode(serviceWith(properties).generateToken(CUSTOMER));

        assertThat(jwt.getSubject()).isEqualTo("7");
        assertThat(jwt.getClaimAsString("username")).isEqualTo("budi");
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        JwtProperties issuer = new JwtProperties("another-secret-with-at-least-32-characters", "transfer-service",
                Duration.ofMinutes(15));
        JwtProperties verifier = new JwtProperties(SECRET, "transfer-service", Duration.ofMinutes(15));
        String token = serviceWith(issuer).generateToken(CUSTOMER);

        assertThatThrownBy(() -> decoderWith(verifier).decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        JwtProperties issuer = new JwtProperties(SECRET, "someone-else", Duration.ofMinutes(15));
        JwtProperties verifier = new JwtProperties(SECRET, "transfer-service", Duration.ofMinutes(15));
        String token = serviceWith(issuer).generateToken(CUSTOMER);

        assertThatThrownBy(() -> decoderWith(verifier).decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        JwtProperties properties = new JwtProperties(SECRET, "transfer-service", Duration.ofMinutes(15));
        // Melewati toleransi clock skew 60 detik dari validator bawaan
        Instant issuedAt = Instant.now().minus(Duration.ofMinutes(20));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject("7")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(Duration.ofMinutes(15)))
                .build();
        String token = securityConfig.jwtEncoder(properties)
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        assertThatThrownBy(() -> decoderWith(properties).decode(token)).isInstanceOf(JwtException.class);
    }
}
