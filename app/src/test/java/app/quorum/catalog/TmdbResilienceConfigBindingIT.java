package app.quorum.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Проверяет ровно то, из-за чего резилиенс не работал раньше: блок
 * {@code resilience4j:} должен быть на корне application.yml, а не под
 * {@code quorum:}. Если он снова уедет под quorum — реестры "tmdb" не
 * получат значения из YAML и тест покраснеет.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TmdbResilienceConfigBindingIT {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("TG_BOT_TOKEN", () -> "unused-in-this-test");
        registry.add("JWT_SECRET", () -> "integration-test-jwt-secret-should-be-long-enough");
        registry.add("TG_APP_NAME", () -> "quorum-it-tests");
        registry.add("TMDB_TOKEN", () -> "unused-in-this-test");
    }

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private RetryRegistry retryRegistry;

    @Autowired
    private RateLimiterRegistry rateLimiterRegistry;

    @Test
    void tmdbInstances_areConfiguredFromApplicationYml() {
        var circuitBreakerConfig = circuitBreakerRegistry.circuitBreaker("tmdb").getCircuitBreakerConfig();
        assertThat(circuitBreakerConfig.getSlidingWindowSize()).isEqualTo(30);
        assertThat(circuitBreakerConfig.getFailureRateThreshold()).isEqualTo(50.0f);
        assertThat(circuitBreakerConfig.getWaitIntervalFunctionInOpenState().apply(1))
                .isEqualTo(Duration.ofSeconds(30).toMillis());

        var retryConfig = retryRegistry.retry("tmdb").getRetryConfig();
        assertThat(retryConfig.getMaxAttempts()).isEqualTo(3);

        var rateLimiterConfig = rateLimiterRegistry.rateLimiter("tmdb").getRateLimiterConfig();
        assertThat(rateLimiterConfig.getLimitForPeriod()).isEqualTo(25);
        assertThat(rateLimiterConfig.getLimitRefreshPeriod()).isEqualTo(Duration.ofSeconds(1));
    }
}
