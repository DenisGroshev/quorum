package app.quorum.catalog.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.quorum.catalog.config.CatalogCacheConfig;
import app.quorum.catalog.config.TmdbProperties;
import app.quorum.catalog.domain.ImageUrlBuilder;
import app.quorum.catalog.infra.MovieRepository;
import app.quorum.catalog.infra.tmdb.TmdbClient;
import app.quorum.catalog.infra.tmdb.TmdbMovieMapper;
import app.quorum.catalog.infra.tmdb.TmdbMovieMapperImpl;
import app.quorum.common.ContentLang;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

/**
 * Проверяет кэш поиска (шаг 6) через реальный Spring cache proxy + WireMock
 * вместо TMDB. {@link CatalogService#search} здесь — единственный метод под
 * тестом; остальные зависимости {@link CatalogService} (база, маппер,
 * построение адресов картинок) кэш поиска не трогают, поэтому замоканы.
 */
class CatalogServiceSearchCacheTest {

    private static WireMockServer wireMock;
    private AnnotationConfigApplicationContext context;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @AfterEach
    void tearDown() {
        wireMock.resetAll();
        if (context != null) {
            context.close();
        }
    }

    private CatalogService service() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        return context.getBean(CatalogService.class);
    }

    private void stubEmptySearchResult() {
        wireMock.stubFor(get(urlPathEqualTo("/search/movie"))
                .willReturn(okJson("{\"page\":1,\"results\":[],\"total_pages\":1,\"total_results\":0}")));
    }

    @Test
    void sameQuery_calledTwice_hitsTmdbOnce() {
        stubEmptySearchResult();
        CatalogService service = service();

        service.search("Интерстеллар", 1, ContentLang.RU);
        service.search("Интерстеллар", 1, ContentLang.RU);

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/search/movie")));
    }

    @Test
    void sameQuery_differentLanguage_hitsTmdbTwice() {
        stubEmptySearchResult();
        CatalogService service = service();

        service.search("interstellar", 1, ContentLang.RU);
        service.search("interstellar", 1, ContentLang.EN);

        wireMock.verify(2, getRequestedFor(urlPathEqualTo("/search/movie")));
    }

    @Test
    void sameQuery_differentCaseAndSpacing_hitsTmdbOnce() {
        stubEmptySearchResult();
        CatalogService service = service();

        service.search("Interstellar", 1, ContentLang.RU);
        service.search("  interstellar  ", 1, ContentLang.RU);

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/search/movie")));
    }

    @Test
    void afterTmdbError_resultIsNotCached_nextCallGoesToTmdbAgain() {
        wireMock.stubFor(
                get(urlPathEqualTo("/search/movie")).willReturn(aResponse().withStatus(500)));
        CatalogService service = service();

        assertThrows(Exception.class, () -> service.search("dune", 1, ContentLang.RU));

        wireMock.resetAll();
        stubEmptySearchResult();

        service.search("dune", 1, ContentLang.RU);

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/search/movie")));
    }

    @Configuration
    @Import(CatalogCacheConfig.class)
    static class TestConfig {

        @Bean
        TmdbProperties tmdbProperties() {
            return new TmdbProperties(
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
        }

        @Bean
        TmdbClient tmdbClient() {
            return new TmdbClient(
                    RestClient.builder().baseUrl(wireMock.baseUrl()).build());
        }

        @Bean
        TmdbMovieMapper tmdbMovieMapper() {
            return new TmdbMovieMapperImpl();
        }

        @Bean
        MovieRepository movieRepository() {
            return Mockito.mock(MovieRepository.class);
        }

        @Bean
        ImageUrlBuilder imageUrlBuilder() {
            return Mockito.mock(ImageUrlBuilder.class);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        CatalogService catalogService(
                TmdbClient client,
                MovieRepository movieRepository,
                TmdbMovieMapper mapper,
                TmdbProperties properties,
                ImageUrlBuilder imageUrlBuilder,
                Clock clock) {
            return new CatalogService(client, movieRepository, mapper, properties, imageUrlBuilder, clock);
        }
    }
}
