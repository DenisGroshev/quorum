package app.quorum.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.catalog.domain.Genre;
import app.quorum.catalog.domain.Movie;
import app.quorum.catalog.domain.MovieGenreRef;
import app.quorum.catalog.infra.CatalogGenreRepository;
import app.quorum.catalog.infra.MovieRepository;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MovieRepositoryIT {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
        registry.add("TG_BOT_TOKEN", () -> "unused-in-this-test");
        registry.add("JWT_SECRET", () -> "integration-test-jwt-secret-should-be-long-enough");
        registry.add("TG_APP_NAME", () -> "quorum-it-tests");
        registry.add("TMDB_TOKEN", () -> "unused-in-this-test");
    }

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private CatalogGenreRepository genreRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private static Movie movie(long tmdbId, Instant syncedAt) {
        Movie movie = new Movie();
        movie.setTmdbId(tmdbId);
        movie.setOriginalTitle("Original " + tmdbId);
        movie.setTitleRu("Ru " + tmdbId);
        movie.setTitleEn("En " + tmdbId);
        movie.setVoteAvg(new BigDecimal("7.5"));
        movie.setVoteCount(100);
        movie.setOriginalLang("en");
        movie.setSyncedAt(syncedAt);
        return movie;
    }

    @Test
    @Transactional // keeps the session open so the lazy `genres` collection can be read below
    void saveAndRead_roundTripsAllFields_includingGenres() {
        genreRepository.saveAll(
                List.of(Genre.of(28, "Боевик", "Action"), Genre.of(878, "Фантастика", "Science Fiction")));

        Movie movie = movie(603, Instant.parse("2026-01-01T00:00:00Z"));
        movie.setReleaseYear((short) 1999);
        movie.setRuntimeMin((short) 136);
        movie.setPosterPath("/poster.jpg");
        movie.setGenres(Set.of(new MovieGenreRef(28, true), new MovieGenreRef(878, false)));
        movieRepository.save(movie);

        Movie loaded = movieRepository.findById(603L).orElseThrow();

        assertThat(loaded.getTitleRu()).isEqualTo("Ru 603");
        assertThat(loaded.getTitleEn()).isEqualTo("En 603");
        assertThat(loaded.getReleaseYear()).isEqualTo((short) 1999);
        assertThat(loaded.getRuntimeMin()).isEqualTo((short) 136);
        assertThat(loaded.getGenres()).hasSize(2);
        assertThat(loaded.getGenres().stream()
                        .filter(MovieGenreRef::isPrimary)
                        .findFirst()
                        .orElseThrow()
                        .getGenreId())
                .isEqualTo(28);
    }

    @Test
    void findAllMoviesByIds_usesOneQuery_regardlessOfHowManyIdsRequested() {
        List<Long> ids = LongStream.rangeClosed(100_000, 100_039).boxed().collect(Collectors.toList());
        Instant now = Instant.now();
        ids.forEach(id -> movieRepository.save(movie(id, now)));

        Statistics statistics =
                entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        List<Movie> found = movieRepository.findAllMoviesByIds(ids);

        assertThat(found).hasSize(40);
        assertThat(statistics.getPrepareStatementCount())
                .as("findAllMoviesByIds() for 40 ids must run a single batched query, not one per id (N+1)")
                .isEqualTo(1);
    }

    @Test
    void findMoviesSyncedBefore_returnsOnlyStaleMovies_orderedOldestFirst() {
        Instant now = Instant.now();
        Movie stale = movie(200_001, now.minus(60, ChronoUnit.DAYS));
        Movie staler = movie(200_002, now.minus(90, ChronoUnit.DAYS));
        Movie fresh = movie(200_003, now);
        movieRepository.saveAll(List.of(stale, staler, fresh));

        List<Movie> result = movieRepository.findMoviesSyncedBefore(now.minus(30, ChronoUnit.DAYS));

        // containsSubsequence (not containsExactly): other test methods share this
        // container and may also have stale-looking movies in the same table.
        assertThat(result).extracting(Movie::getTmdbId).containsSubsequence(200_002L, 200_001L);
        assertThat(result).extracting(Movie::getTmdbId).doesNotContain(200_003L);
    }
}
