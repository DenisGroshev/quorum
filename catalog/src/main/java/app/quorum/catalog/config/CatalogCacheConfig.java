package app.quorum.catalog.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// proxyTargetClass: CatalogService реализует CatalogFacade, а @EnableCaching по
// умолчанию предпочитает JDK-прокси по интерфейсу, если он есть — тогда бин в
// контексте перестаёт быть присвояем конкретному классу CatalogService (только
// CatalogFacade). Spring Boot сам выставляет proxyTargetClass=true при
// автоконфигурации, но здесь это явно, чтобы не зависеть от окружения.
@Configuration(proxyBeanMethods = false)
@EnableCaching(proxyTargetClass = true)
public class CatalogCacheConfig {

    public static final String MOVIE_SEARCH_CACHE = "movieSearch";

    @Bean
    public CacheManager cacheManager(TmdbProperties properties) {
        CaffeineCacheManager manager = new CaffeineCacheManager(MOVIE_SEARCH_CACHE);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(properties.searchCacheTtl())
                .maximumSize(properties.searchCacheSize()));
        return manager;
    }
}
