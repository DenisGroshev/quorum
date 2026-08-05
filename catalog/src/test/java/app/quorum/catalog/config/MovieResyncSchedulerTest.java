package app.quorum.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.quorum.catalog.domain.Movie;
import app.quorum.catalog.infra.MovieRepository;
import app.quorum.catalog.service.CatalogService;
import app.quorum.common.ContentLang;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class MovieResyncSchedulerTest {

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

    @Mock
    private MovieRepository movieRepository;

    @Mock
    private CatalogService catalogService;

    private MovieResyncScheduler scheduler() {
        return new MovieResyncScheduler(movieRepository, catalogService, PROPERTIES, CLOCK);
    }

    private static Movie movie(long id) {
        Movie movie = new Movie();
        movie.setTmdbId(id);
        return movie;
    }

    @Test
    void resyncStaleMovies_fetchesBatchOlderThanResyncThreshold_andRefreshesEach() {
        Instant expectedThreshold = CLOCK.instant().minus(PROPERTIES.resyncAfter());
        when(movieRepository.findMoviesSyncedBefore(org.mockito.ArgumentMatchers.eq(expectedThreshold), any()))
                .thenReturn(List.of(movie(1), movie(2), movie(3)));

        scheduler().resyncStaleMovies();

        verify(catalogService).getOrFetch(1L, ContentLang.RU);
        verify(catalogService).getOrFetch(2L, ContentLang.RU);
        verify(catalogService).getOrFetch(3L, ContentLang.RU);
    }

    @Test
    void resyncStaleMovies_requestsOnlyOneBatch_notTheWholeTable() {
        when(movieRepository.findMoviesSyncedBefore(any(), any())).thenReturn(List.of());

        scheduler().resyncStaleMovies();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(movieRepository).findMoviesSyncedBefore(any(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(MovieResyncScheduler.BATCH_SIZE);
    }

    @Test
    void resyncStaleMovies_oneMovieFails_restOfBatchStillProcessed() {
        when(movieRepository.findMoviesSyncedBefore(any(), any())).thenReturn(List.of(movie(1), movie(2), movie(3)));
        when(catalogService.getOrFetch(2L, ContentLang.RU)).thenThrow(new RuntimeException("boom"));

        assertThatCode(() -> scheduler().resyncStaleMovies()).doesNotThrowAnyException();

        verify(catalogService).getOrFetch(1L, ContentLang.RU);
        verify(catalogService, times(1)).getOrFetch(2L, ContentLang.RU);
        verify(catalogService).getOrFetch(3L, ContentLang.RU);
    }
}
