package app.quorum.catalog;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.catalog.config.GenreSyncRunner;
import app.quorum.catalog.infra.CatalogGenreRepository;
import app.quorum.catalog.infra.tmdb.TmdbClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Шаг 11, чек-лист: "повторный старт не создаёт дубликатов". Мок-тест этого
 * не докажет (репозиторий там замокан) — нужна настоящая база: save() по уже
 * существующему id должен быть UPDATE, а не INSERT.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class GenreSyncRunnerIT {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static WireMockServer wireMock;

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

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        wireMock.stubFor(get(urlPathEqualTo("/genre/movie/list"))
                .withQueryParam("language", com.github.tomakehurst.wiremock.client.WireMock.equalTo("ru-RU"))
                .willReturn(okJson("{\"genres\":[{\"id\":28,\"name\":\"Боевик\"},{\"id\":35,\"name\":\"Комедия\"}]}")));
        wireMock.stubFor(get(urlPathEqualTo("/genre/movie/list"))
                .withQueryParam("language", com.github.tomakehurst.wiremock.client.WireMock.equalTo("en-US"))
                .willReturn(okJson("{\"genres\":[{\"id\":28,\"name\":\"Action\"},{\"id\":35,\"name\":\"Comedy\"}]}")));
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @Autowired
    private CatalogGenreRepository genreRepository;

    @Test
    void runningTwice_doesNotCreateDuplicates() {
        TmdbClient wireMockBackedClient =
                new TmdbClient(RestClient.builder().baseUrl(wireMock.baseUrl()).build());
        GenreSyncRunner runner = new GenreSyncRunner(wireMockBackedClient, genreRepository);

        runner.run(null);
        long afterFirstRun = genreRepository.count();
        runner.run(null);
        long afterSecondRun = genreRepository.count();

        assertThat(afterFirstRun).isEqualTo(2);
        assertThat(afterSecondRun).isEqualTo(2);
        assertThat(genreRepository.findById(28).orElseThrow().getNameRu()).isEqualTo("Боевик");
    }
}
