package app.quorum.catalog.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "quorum.tmdb")
public record TmdbProperties(
        @NotNull String baseUrl,
        @NotNull String apiToken,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Positive int ratePerSec,
        @NotNull Duration resyncAfter,
        @NotNull Duration searchCacheTtl,
        @Positive long searchCacheSize,
        @NotNull String posterSizeList,
        @NotNull String posterSizeDetails) {}
