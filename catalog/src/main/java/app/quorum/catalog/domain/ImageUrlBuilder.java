package app.quorum.catalog.domain;

import app.quorum.catalog.infra.tmdb.TmdbClient;
import app.quorum.catalog.infra.tmdb.dto.TmdbImagesConfig;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class ImageUrlBuilder {

    private static final Object CONFIG_KEY = new Object();

    private final Cache<Object, TmdbImagesConfig> configCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofDays(1))
            .maximumSize(1)
            .build();

    private final TmdbClient tmdbClient;

    public ImageUrlBuilder(TmdbClient tmdbClient) {
        this.tmdbClient = tmdbClient;
    }

    public String posterUrl(String posterPath, String size) {
        if (posterPath == null || posterPath.isBlank()) {
            return null;
        }
        TmdbImagesConfig config =
                configCache.get(CONFIG_KEY, key -> tmdbClient.getConfiguration().images());
        return stripTrailingSlash(config.secureBaseUrl()) + "/" + stripSlashes(size) + "/"
                + stripLeadingSlash(posterPath);
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String stripLeadingSlash(String value) {
        return value.startsWith("/") ? value.substring(1) : value;
    }

    private static String stripSlashes(String value) {
        return stripLeadingSlash(stripTrailingSlash(value));
    }
}
