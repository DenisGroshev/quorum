package app.quorum.catalog.infra.tmdb;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.quorum.catalog.config.TmdbClientConfig;
import app.quorum.catalog.config.TmdbProperties;
import app.quorum.common.ContentLang;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * Шаг 7: три механизма устойчивости, собранные вручную в {@link TmdbClient}
 * (размыкатель снаружи → повторы → ограничитель скорости → HTTP-вызов).
 * Настройки здесь — те же значения из application.yml, но собранные в коде
 * (без Spring-контекста) с уменьшенными окнами/паузами, чтобы тест был
 * быстрым и детерминированным.
 */
class TmdbClientResilienceTest {

    private WireMockServer wireMock;

    @AfterEach
    void tearDown() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    private TmdbClient clientWith(RetryConfig retryConfig, CircuitBreakerConfig cbConfig) {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();

        TmdbProperties properties = new TmdbProperties(
                wireMock.baseUrl(),
                "test-token",
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                25,
                Duration.ofDays(30),
                Duration.ofMinutes(15),
                10_000,
                "w185",
                "w500");
        RestClient restClient = new TmdbClientConfig().tmdbServiceRestClient(RestClient.builder(), properties);

        return new TmdbClient(
                restClient,
                CircuitBreakerRegistry.of(cbConfig),
                RetryRegistry.of(retryConfig),
                RateLimiterRegistry.of(RateLimiterConfig.custom()
                        .limitForPeriod(1000)
                        .limitRefreshPeriod(Duration.ofMillis(1))
                        .timeoutDuration(Duration.ofSeconds(1))
                        .build()));
    }

    private static RetryConfig fastRetryConfig() {
        return RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .retryOnException(e -> e instanceof IOException || e instanceof HttpServerErrorException)
                .build();
    }

    private static CircuitBreakerConfig permissiveCircuitBreakerConfig() {
        // Достаточно большое окно, чтобы не размыкаться в тестах retry/404.
        return CircuitBreakerConfig.custom()
                .slidingWindowSize(100)
                .failureRateThreshold(90)
                .build();
    }

    @Test
    void serverError_isRetried_exactlyThreeAttemptsTotal() {
        TmdbClient client = clientWith(fastRetryConfig(), permissiveCircuitBreakerConfig());
        wireMock.stubFor(
                get(urlPathEqualTo("/search/movie")).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.searchMovies("dune", 1, ContentLang.RU));

        wireMock.verify(3, getRequestedFor(urlPathEqualTo("/search/movie")));
    }

    @Test
    void notFound_isNotRetried_exactlyOneAttempt() {
        TmdbClient client = clientWith(fastRetryConfig(), permissiveCircuitBreakerConfig());
        wireMock.stubFor(
                get(urlPathEqualTo("/movie/999")).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> client.getMovie(999L, ContentLang.RU));

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/movie/999")));
    }

    @Test
    void manyFailures_openCircuit_thenShortCircuitsWithoutCallingTmdb() {
        // maxAttempts=1: изолируем размыкатель от повторов — считаем "один
        // логический вызов = одна ошибка", как и должно быть при правильном
        // порядке (размыкатель снаружи повторов).
        RetryConfig noRetry = RetryConfig.custom().maxAttempts(1).build();
        CircuitBreakerConfig quickToOpen = CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build();
        TmdbClient client = clientWith(noRetry, quickToOpen);
        wireMock.stubFor(
                get(urlPathEqualTo("/search/movie")).willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> client.searchMovies("dune", 1, ContentLang.RU));
        }
        wireMock.verify(4, getRequestedFor(urlPathEqualTo("/search/movie")));

        // Пятый вызов должен быть отклонён размыкателем, а не уйти в WireMock.
        assertThatThrownBy(() -> client.searchMovies("dune", 1, ContentLang.RU))
                .isInstanceOf(CallNotPermittedException.class);
        wireMock.verify(4, getRequestedFor(urlPathEqualTo("/search/movie")));
    }

    @Test
    void afterWaitDuration_circuitHalfOpens_andAllowsAProbeCallThrough() throws InterruptedException {
        RetryConfig noRetry = RetryConfig.custom().maxAttempts(1).build();
        CircuitBreakerConfig quickToOpenAndRecover = CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(200))
                .permittedNumberOfCallsInHalfOpenState(1)
                .build();
        TmdbClient client = clientWith(noRetry, quickToOpenAndRecover);
        wireMock.stubFor(
                get(urlPathEqualTo("/search/movie")).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.searchMovies("dune", 1, ContentLang.RU));
        assertThatThrownBy(() -> client.searchMovies("dune", 1, ContentLang.RU));
        // Размыкатель открыт — следующий вызов должен быть отклонён мгновенно.
        assertThatThrownBy(() -> client.searchMovies("dune", 1, ContentLang.RU))
                .isInstanceOf(CallNotPermittedException.class);

        Thread.sleep(300);
        wireMock.resetAll();
        wireMock.stubFor(get(urlPathEqualTo("/search/movie"))
                .willReturn(okJson("{\"page\":1,\"results\":[],\"total_pages\":1,\"total_results\":0}")));

        client.searchMovies("dune", 1, ContentLang.RU);

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/search/movie")));
    }
}
