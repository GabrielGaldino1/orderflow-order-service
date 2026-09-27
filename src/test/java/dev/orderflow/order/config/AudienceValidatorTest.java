package dev.orderflow.order.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AudienceValidatorTest {

    private final AudienceValidator validator = new AudienceValidator("orderflow-api");

    @Test
    void acceptsTheRequiredAudience() {
        assertThat(validator.validate(jwt(List.of("orderflow-api"))).hasErrors()).isFalse();
    }

    @Test
    void rejectsAnotherAudience() {
        assertThat(validator.validate(jwt(List.of("another-api"))).hasErrors()).isTrue();
    }

    private Jwt jwt(List<String> audiences) {
        Instant now = Instant.now();
        return new Jwt(
                "token-value",
                now,
                now.plusSeconds(60),
                Map.of("alg", "none"),
                Map.of("sub", "customer-123", "aud", audiences));
    }
}
