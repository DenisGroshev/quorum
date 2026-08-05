package app.quorum.catalog.infra.tmdb;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.quorum.catalog.config.TmdbClientConfig;
import app.quorum.catalog.config.TmdbProperties;
import app.quorum.common.ContentLang;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Проверяет то, что чек-лист шага 3 требует, но что не было покрыто тестами
 * раньше: {@code include_adult=false} в поиске, формат языка TMDB
 * (ru-RU/en-US, не голые ru/en) и что таймаут реально прерывает медленный
 * ответ, а не висит до победного.
 */
class TmdbClientTest {

    private WireMockServer wireMock;

    @AfterEach
    void tearDown() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    private TmdbClient clientWithTimeouts(Duration connectTimeout, Duration readTimeout) {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();

        TmdbProperties properties = new TmdbProperties(
                wireMock.baseUrl(),
                "test-token",
                connectTimeout,
                readTimeout,
                25,
                Duration.ofDays(30),
                Duration.ofMinutes(15),
                10_000,
                "w185",
                "w500");

        RestClient restClient = new TmdbClientConfig().tmdbServiceRestClient(RestClient.builder(), properties);
        return new TmdbClient(restClient);
    }

    private TmdbClient client() {
        return clientWithTimeouts(Duration.ofSeconds(3), Duration.ofSeconds(5));
    }

    @Test
    void searchMovies_alwaysSendsIncludeAdultFalse() {
        TmdbClient client = client();
        wireMock.stubFor(get(urlPathEqualTo("/search/movie"))
                .willReturn(okJson("{\"page\":1,\"results\":[],\"total_pages\":1,\"total_results\":0}")));

        client.searchMovies("dune", 1, ContentLang.RU);

        wireMock.verify(getRequestedFor(urlPathEqualTo("/search/movie"))
                .withQueryParam("include_adult", com.github.tomakehurst.wiremock.client.WireMock.equalTo("false")));
    }

    @Test
    void searchMovies_ru_usesFullTmdbLanguageTag_notBareCode() {
        TmdbClient client = client();
        wireMock.stubFor(get(urlPathEqualTo("/search/movie"))
                .willReturn(okJson("{\"page\":1,\"results\":[],\"total_pages\":1,\"total_results\":0}")));

        client.searchMovies("dune", 1, ContentLang.RU);

        wireMock.verify(getRequestedFor(urlPathEqualTo("/search/movie"))
                .withQueryParam("language", com.github.tomakehurst.wiremock.client.WireMock.equalTo("ru-RU")));
    }

    @Test
    void searchMovies_en_usesFullTmdbLanguageTag_notBareCode() {
        TmdbClient client = client();
        wireMock.stubFor(get(urlPathEqualTo("/search/movie"))
                .willReturn(okJson("{\"page\":1,\"results\":[],\"total_pages\":1,\"total_results\":0}")));

        client.searchMovies("dune", 1, ContentLang.EN);

        wireMock.verify(getRequestedFor(urlPathEqualTo("/search/movie"))
                .withQueryParam("language", com.github.tomakehurst.wiremock.client.WireMock.equalTo("en-US")));
    }

    @Test
    void getMovie_usesFullTmdbLanguageTag() {
        TmdbClient client = client();
        wireMock.stubFor(
                get(urlPathEqualTo("/movie/603"))
                        .willReturn(
                                okJson(
                                        "{\"id\":603,\"title\":\"t\",\"original_title\":\"t\",\"overview\":\"o\",\"vote_average\":8.1,\"vote_count\":1}")));

        client.getMovie(603L, ContentLang.EN);

        wireMock.verify(getRequestedFor(urlPathEqualTo("/movie/603"))
                .withQueryParam("language", com.github.tomakehurst.wiremock.client.WireMock.equalTo("en-US")));
    }

    @Test
    void slowResponse_isInterruptedByReadTimeout_doesNotHangForFullDelay() {
        TmdbClient client = clientWithTimeouts(Duration.ofSeconds(3), Duration.ofMillis(300));
        wireMock.stubFor(get(urlPathEqualTo("/search/movie"))
                .willReturn(aResponse().withFixedDelay(10_000).withBody("{}")));

        Instant start = Instant.now();

        assertThatThrownBy(() -> client.searchMovies("slow", 1, ContentLang.RU)).isInstanceOf(RuntimeException.class);

        Duration elapsed = Duration.between(start, Instant.now());
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
    }
}
