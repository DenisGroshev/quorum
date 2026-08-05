package app.quorum.catalog.infra.tmdb;

import app.quorum.catalog.infra.tmdb.dto.TmdbConfiguration;
import app.quorum.catalog.infra.tmdb.dto.TmdbGenreListResponse;
import app.quorum.catalog.infra.tmdb.dto.TmdbMovieFull;
import app.quorum.catalog.infra.tmdb.dto.TmdbSearchResponse;
import app.quorum.common.ContentLang;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class TmdbClient {

    private static final String INSTANCE_NAME = "tmdb";

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final RateLimiter rateLimiter;

    /**
     * Настоящий рантайм: три реестра приходят из автоконфигурации Spring Boot,
     * собранной из {@code resilience4j.*} в application.yml (шаг 7).
     */
    @Autowired
    public TmdbClient(
            @Qualifier("tmdbServiceRestClient") RestClient restClient,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            RateLimiterRegistry rateLimiterRegistry) {
        this.restClient = restClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME);
        this.retry = retryRegistry.retry(INSTANCE_NAME);
        this.rateLimiter = rateLimiterRegistry.rateLimiter(INSTANCE_NAME);
    }

    public TmdbClient(RestClient restClient) {
        this(
                restClient,
                CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults()),
                RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build()),
                RateLimiterRegistry.of(RateLimiterConfig.custom()
                        .limitForPeriod(Integer.MAX_VALUE)
                        .limitRefreshPeriod(Duration.ofSeconds(1))
                        .timeoutDuration(Duration.ofSeconds(5))
                        .build()));
    }

    public TmdbSearchResponse searchMovies(String query, int page, ContentLang lang) {
        return execute(() -> restClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/search/movie")
                        .queryParam("query", query)
                        .queryParam("page", page)
                        .queryParam("language", languageCode(lang))
                        .queryParam("include_adult", false)
                        .build())
                .retrieve()
                .body(TmdbSearchResponse.class));
    }

    public TmdbMovieFull getMovie(long id, ContentLang lang) {
        return execute(() -> restClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/movie/{id}")
                        .queryParam("language", languageCode(lang))
                        .build(id))
                .retrieve()
                .body(TmdbMovieFull.class));
    }

    public TmdbSearchResponse getTrending(ContentLang lang, int page) {
        return execute(() -> restClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/trending/movie/week")
                        .queryParam("language", languageCode(lang))
                        .queryParam("page", page)
                        .build())
                .retrieve()
                .body(TmdbSearchResponse.class));
    }

    public TmdbGenreListResponse getGenres(ContentLang lang) {
        return execute(() -> restClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/genre/movie/list")
                        .queryParam("language", languageCode(lang))
                        .build())
                .retrieve()
                .body(TmdbGenreListResponse.class));
    }

    public TmdbConfiguration getConfiguration() {
        return execute(() -> restClient.get().uri("/configuration").retrieve().body(TmdbConfiguration.class));
    }

    /**
     * Размыкатель цепи снаружи -> повторы -> ограничитель скорости -> сам
     */
    private <T> T execute(Supplier<T> call) {
        Supplier<T> withRateLimiter = RateLimiter.decorateSupplier(rateLimiter, call);
        Supplier<T> withRetry = Retry.decorateSupplier(retry, withRateLimiter);
        Supplier<T> withCircuitBreaker = CircuitBreaker.decorateSupplier(circuitBreaker, withRetry);
        return withCircuitBreaker.get();
    }

    // TMDB требует полный языковой тег (ru-RU/en-US), а не голый код языка
    private String languageCode(ContentLang lang) {
        return switch (lang) {
            case RU -> "ru-RU";
            case EN -> "en-US";
        };
    }
}
