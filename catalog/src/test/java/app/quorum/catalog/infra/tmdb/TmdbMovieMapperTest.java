package app.quorum.catalog.infra.tmdb;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.catalog.domain.Movie;
import app.quorum.catalog.domain.MovieGenreRef;
import app.quorum.catalog.infra.tmdb.dto.TmdbGenre;
import app.quorum.catalog.infra.tmdb.dto.TmdbMovieFull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TmdbMovieMapperTest {

    private final TmdbMovieMapper mapper = new TmdbMovieMapperImpl();

    private static TmdbMovieFull fullMovie(String title, String overview, String releaseDate, Integer runtime) {
        return new TmdbMovieFull(
                603,
                title,
                "Original Title",
                overview,
                "/poster.jpg",
                releaseDate,
                runtime,
                List.of(new TmdbGenre(28, "Action"), new TmdbGenre(878, "Science Fiction")),
                "en",
                new BigDecimal("8.2"),
                25000);
    }

    @Test
    void toMovie_fillsBothLanguages_fromTwoResponses() {
        TmdbMovieFull ru = fullMovie("Матрица", "Хакер узнаёт правду о реальности", "1999-03-31", 136);
        TmdbMovieFull en = fullMovie("The Matrix", "A hacker learns the truth about reality", "1999-03-31", 136);

        Movie movie = mapper.toMovie(ru, en, Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(movie.getTitleRu()).isEqualTo("Матрица");
        assertThat(movie.getTitleEn()).isEqualTo("The Matrix");
        assertThat(movie.getOverviewRu()).isEqualTo("Хакер узнаёт правду о реальности");
        assertThat(movie.getOverviewEn()).isEqualTo("A hacker learns the truth about reality");
    }

    @Test
    void toMovie_doesNotUseOriginalTitleAsEnglishTitle() {
        // original_title у TMDB не совпадает с английским названием (например, для
        // "Левиафана" оригинал русский) — englishName должен прийти строго из
        // английского ответа, а не из original_title.
        TmdbMovieFull ru = fullMovie("Левиафан", "overview ru", "2014-11-05", 141);
        TmdbMovieFull en = fullMovie("Leviathan", "overview en", "2014-11-05", 141);

        Movie movie = mapper.toMovie(ru, en, Instant.now());

        assertThat(movie.getOriginalTitle()).isEqualTo("Original Title");
        assertThat(movie.getTitleEn()).isEqualTo("Leviathan");
        assertThat(movie.getTitleEn()).isNotEqualTo(movie.getOriginalTitle());
    }

    @Test
    void toMovie_languageIndependentFields_takenFromRuResponse() {
        TmdbMovieFull ru = fullMovie("ru title", "ru overview", "1999-03-31", 136);
        TmdbMovieFull en = fullMovie("en title", "en overview", "1999-03-31", 136);

        Movie movie = mapper.toMovie(ru, en, Instant.now());

        assertThat(movie.getTmdbId()).isEqualTo(603L);
        assertThat(movie.getPosterPath()).isEqualTo("/poster.jpg");
        assertThat(movie.getVoteAvg()).isEqualByComparingTo("8.2");
        assertThat(movie.getVoteCount()).isEqualTo(25000);
        assertThat(movie.getOriginalLang()).isEqualTo("en");
    }

    @Test
    void toMovie_emptyReleaseDate_yieldsNullYear_notZeroNorException() {
        TmdbMovieFull ru = fullMovie("title", "overview", "", 100);
        TmdbMovieFull en = fullMovie("title", "overview", "", 100);

        Movie movie = mapper.toMovie(ru, en, Instant.now());

        assertThat(movie.getReleaseYear()).isNull();
    }

    @Test
    void toMovie_normalReleaseDate_parsesYear() {
        TmdbMovieFull ru = fullMovie("title", "overview", "2014-11-05", 100);
        TmdbMovieFull en = fullMovie("title", "overview", "2014-11-05", 100);

        Movie movie = mapper.toMovie(ru, en, Instant.now());

        assertThat(movie.getReleaseYear()).isEqualTo((short) 2014);
    }

    @Test
    void toMovie_missingPosterAndDate_doesNotBreakMapping() {
        TmdbMovieFull ru = new TmdbMovieFull(
                603, "title", "original", "overview", null, null, null, List.of(), "en", new BigDecimal("0"), 0);
        TmdbMovieFull en = ru;

        Movie movie = mapper.toMovie(ru, en, Instant.now());

        assertThat(movie.getPosterPath()).isNull();
        assertThat(movie.getReleaseYear()).isNull();
        assertThat(movie.getRuntimeMin()).isNull();
        assertThat(movie.getGenres()).isEmpty();
    }

    @Test
    void toMovie_runtimeNull_mapsToNullNotZero() {
        TmdbMovieFull ru = fullMovie("title", "overview", "2014-11-05", null);
        TmdbMovieFull en = ru;

        Movie movie = mapper.toMovie(ru, en, Instant.now());

        assertThat(movie.getRuntimeMin()).isNull();
    }

    @Test
    void toMovie_firstGenreIsMarkedPrimary() {
        TmdbMovieFull ru = fullMovie("title", "overview", "2014-11-05", 100);
        TmdbMovieFull en = ru;

        Movie movie = mapper.toMovie(ru, en, Instant.now());

        MovieGenreRef primary = movie.getGenres().stream()
                .filter(MovieGenreRef::isPrimary)
                .findFirst()
                .orElseThrow();
        assertThat(primary.getGenreId()).isEqualTo(28);
        assertThat(movie.getGenres()).hasSize(2);
        assertThat(movie.getGenres().stream().filter(g -> !g.isPrimary())).hasSize(1);
    }

    @Test
    void toMovie_setsSyncedAtFromGivenInstant() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        TmdbMovieFull ru = fullMovie("title", "overview", "2014-11-05", 100);

        Movie movie = mapper.toMovie(ru, ru, now);

        assertThat(movie.getSyncedAt()).isEqualTo(now);
    }

    @Test
    void updateMovie_refreshesFields_butKeepsExistingId() {
        Movie existing = mapper.toMovie(
                fullMovie("old ru", "old overview ru", "2000-01-01", 90),
                fullMovie("old en", "old overview en", "2000-01-01", 90),
                Instant.parse("2020-01-01T00:00:00Z"));
        long originalId = existing.getTmdbId();

        Instant refreshedAt = Instant.parse("2026-01-01T00:00:00Z");
        mapper.updateMovie(
                existing,
                fullMovie("new ru", "new overview ru", "2001-02-03", 95),
                fullMovie("new en", "new overview en", "2001-02-03", 95),
                refreshedAt);

        assertThat(existing.getTmdbId()).isEqualTo(originalId);
        assertThat(existing.getTitleRu()).isEqualTo("new ru");
        assertThat(existing.getTitleEn()).isEqualTo("new en");
        assertThat(existing.getReleaseYear()).isEqualTo((short) 2001);
        assertThat(existing.getSyncedAt()).isEqualTo(refreshedAt);
    }
}
