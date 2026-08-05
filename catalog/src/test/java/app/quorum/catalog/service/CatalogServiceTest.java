package app.quorum.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import app.quorum.catalog.api.MovieDto;
import app.quorum.catalog.config.TmdbProperties;
import app.quorum.catalog.domain.ImageUrlBuilder;
import app.quorum.catalog.domain.Movie;
import app.quorum.catalog.infra.MovieRepository;
import app.quorum.catalog.infra.tmdb.TmdbClient;
import app.quorum.catalog.infra.tmdb.TmdbMovieMapper;
import app.quorum.catalog.infra.tmdb.TmdbMovieMapperImpl;
import app.quorum.catalog.infra.tmdb.dto.TmdbGenre;
import app.quorum.catalog.infra.tmdb.dto.TmdbMovieFull;
import app.quorum.common.ContentLang;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC);

    private static final TmdbProperties PROPERTIES = new TmdbProperties(
            "https://api.themoviedb.org/3",
            "test-token",
            Duration.ofSeconds(3),
            Duration.ofSeconds(5),
            25,
            Duration.ofDays(30),
            Duration.ofMinutes(15),
            10_000,
            "w185",
            "w500");

    private final TmdbMovieMapper mapper = new TmdbMovieMapperImpl();

    @Mock
    private TmdbClient tmdbClient;

    @Mock
    private MovieRepository movieRepository;

    @Mock
    private ImageUrlBuilder imageUrlBuilder;

    private CatalogService service() {
        return new CatalogService(tmdbClient, movieRepository, mapper, PROPERTIES, imageUrlBuilder, CLOCK);
    }

    private static TmdbMovieFull fullMovie(String title, String overview) {
        return new TmdbMovieFull(
                603,
                title,
                "Original Title",
                overview,
                "/poster.jpg",
                "1999-03-31",
                136,
                List.of(new TmdbGenre(28, "Action")),
                "en",
                new BigDecimal("8.2"),
                25000);
    }

    private static Movie movie(long tmdbId, Instant syncedAt) {
        Movie movie = new Movie();
        movie.setTmdbId(tmdbId);
        movie.setOriginalTitle("Original " + tmdbId);
        movie.setTitleRu("Ru " + tmdbId);
        movie.setTitleEn("En " + tmdbId);
        movie.setOverviewRu("Overview ru " + tmdbId);
        movie.setOverviewEn("Overview en " + tmdbId);
        movie.setVoteAvg(new BigDecimal("7.5"));
        movie.setVoteCount(100);
        movie.setSyncedAt(syncedAt);
        return movie;
    }

    @Test
    void getOrFetch_firstCallSaves_secondCallDoesNotHitNetwork() {
        when(movieRepository.findById(603L)).thenReturn(Optional.empty());
        when(tmdbClient.getMovie(603L, ContentLang.RU)).thenReturn(fullMovie("Матрица", "ru overview"));
        when(tmdbClient.getMovie(603L, ContentLang.EN)).thenReturn(fullMovie("The Matrix", "en overview"));
        AtomicReference<Movie> saved = new AtomicReference<>();
        when(movieRepository.save(any(Movie.class))).thenAnswer(inv -> {
            saved.set(inv.getArgument(0));
            return saved.get();
        });

        CatalogService service = service();
        MovieDto first = service.getOrFetch(603L, ContentLang.RU);

        assertThat(first.title()).isEqualTo("Матрица");
        verify(tmdbClient, times(1)).getMovie(603L, ContentLang.RU);
        verify(tmdbClient, times(1)).getMovie(603L, ContentLang.EN);

        // second call: DB now has what the first call saved
        when(movieRepository.findById(603L)).thenReturn(Optional.of(saved.get()));
        clearInvocations(tmdbClient);

        service.getOrFetch(603L, ContentLang.RU);

        verifyNoInteractions(tmdbClient);
    }

    @Test
    void getOrFetch_freshMovieInDb_doesNotHitNetwork() {
        Movie fresh = movie(603, CLOCK.instant());
        when(movieRepository.findById(603L)).thenReturn(Optional.of(fresh));

        MovieDto dto = service().getOrFetch(603L, ContentLang.RU);

        assertThat(dto.title()).isEqualTo("Ru 603");
        verifyNoInteractions(tmdbClient);
    }

    @Test
    void getOrFetch_staleMovie_tmdbAvailable_refreshesAndSaves() {
        Instant staleSince = CLOCK.instant().minus(PROPERTIES.resyncAfter()).minus(Duration.ofDays(1));
        Movie stale = movie(603, staleSince);
        when(movieRepository.findById(603L)).thenReturn(Optional.of(stale));
        when(tmdbClient.getMovie(603L, ContentLang.RU)).thenReturn(fullMovie("New Ru Title", "new ru overview"));
        when(tmdbClient.getMovie(603L, ContentLang.EN)).thenReturn(fullMovie("New En Title", "new en overview"));
        when(movieRepository.save(any(Movie.class))).thenAnswer(inv -> inv.getArgument(0));

        MovieDto dto = service().getOrFetch(603L, ContentLang.RU);

        assertThat(dto.title()).isEqualTo("New Ru Title");
        verify(movieRepository).save(stale);
    }

    @Test
    void getOrFetch_staleMovie_tmdbDown_returnsStaleDataWithoutThrowing() {
        Instant staleSince = CLOCK.instant().minus(PROPERTIES.resyncAfter()).minus(Duration.ofDays(1));
        Movie stale = movie(603, staleSince);
        when(movieRepository.findById(603L)).thenReturn(Optional.of(stale));
        when(tmdbClient.getMovie(anyLong(), any())).thenThrow(new RestClientException("TMDB is down"));

        MovieDto dto = service().getOrFetch(603L, ContentLang.RU);

        assertThat(dto.tmdbId()).isEqualTo(603L);
        assertThat(dto.title()).isEqualTo("Ru 603");
        verify(movieRepository, never()).save(any());
    }

    @Test
    void getCached_neverCallsTmdb() {
        Movie m1 = movie(1, CLOCK.instant());
        Movie m2 = movie(2, CLOCK.instant());
        when(movieRepository.findAllMoviesByIds(List.of(1L, 2L))).thenReturn(List.of(m1, m2));

        var result = service().getCached(List.of(1L, 2L), ContentLang.RU);

        assertThat(result).hasSize(2);
        assertThat(result.get(1L).title()).isEqualTo("Ru 1");
        verifyNoInteractions(tmdbClient);
    }

    @Test
    void popularByGenres_returnsFromDb_neverCallsTmdb() {
        Movie m1 = movie(1, CLOCK.instant());
        when(movieRepository.findPopularByGenreIds(eq(Set.of(28)), any())).thenReturn(List.of(m1));

        List<MovieDto> result = service().popularByGenres(Set.of(28), 10, ContentLang.RU);

        assertThat(result).hasSize(1);
        verifyNoInteractions(tmdbClient);
    }
}
