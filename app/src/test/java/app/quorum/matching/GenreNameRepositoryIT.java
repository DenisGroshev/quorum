package app.quorum.matching;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.matching.infra.GenreNameRepository;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class GenreNameRepositoryIT {

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
    private GenreNameRepository genreNameRepository;

    @Autowired
    private JdbcClient jdbcClient;

    private void insertGenre(int id, String nameRu, String nameEn) {
        jdbcClient
                .sql("INSERT INTO genres (id, name_ru, name_en) VALUES (:id, :nameRu, :nameEn)")
                .param("id", id)
                .param("nameRu", nameRu)
                .param("nameEn", nameEn)
                .update();
    }

    @Test
    void namesRu_returnsRussianNamesForRequestedIds() {
        insertGenre(28, "Боевик", "Action");
        insertGenre(878, "Фантастика", "Science Fiction");
        insertGenre(18, "Драма", "Drama");

        Map<Integer, String> names = genreNameRepository.namesRu(Set.of(28, 878));

        assertThat(names).containsOnly(Map.entry(28, "Боевик"), Map.entry(878, "Фантастика"));
    }

    @Test
    void namesRu_emptyIdSet_returnsEmptyMapWithoutQueryError() {
        assertThat(genreNameRepository.namesRu(Set.of())).isEmpty();
    }
}
