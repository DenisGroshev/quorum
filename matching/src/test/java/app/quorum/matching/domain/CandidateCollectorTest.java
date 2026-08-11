package app.quorum.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.quorum.catalog.api.CatalogFacade;
import app.quorum.catalog.api.MovieDto;
import app.quorum.common.ContentLang;
import app.quorum.matching.config.MatchingProperties;
import app.quorum.matching.config.MatchingProperties.Penalties;
import app.quorum.matching.config.MatchingProperties.Weights;
import app.quorum.session.api.SessionHistoryQueries;
import app.quorum.session.domain.DeckRequest;
import app.quorum.watchlist.api.WatchlistAgg;
import app.quorum.watchlist.api.WatchlistQueries;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CandidateCollectorTest {

    private static final MatchingProperties PROPERTIES = new MatchingProperties(
            "w1", new Weights(45, 20, 15, 10, 10), new Penalties(25, 15, 10), 150, 180, 100, 0.3, 0.4, 3, 0.5);

    @Mock
    private WatchlistQueries watchlistQueries;

    @Mock
    private CatalogFacade catalogFacade;

    @Mock
    private SessionHistoryQueries sessionHistoryQueries;

    private CandidateCollector collector;

    @BeforeEach
    void setUp() {
        collector = new CandidateCollector(watchlistQueries, catalogFacade, sessionHistoryQueries, PROPERTIES);
        when(sessionHistoryQueries.pastWinnersForExactMemberSet(anySet())).thenReturn(Set.of());
    }

    private static DeckRequest requestOf(int deckSize, boolean watchlistOnly) {
        return new DeckRequest(
                UUID.randomUUID(), List.of(UUID.randomUUID(), UUID.randomUUID()), deckSize, watchlistOnly);
    }

    private static MovieDto movieDto(
            long tmdbId, Set<Integer> genreIds, Integer runtimeMin, String voteAverage, int voteCount, String lang) {
        return new MovieDto(
                tmdbId,
                "Title " + tmdbId,
                "Original " + tmdbId,
                2020,
                runtimeMin,
                "poster.jpg",
                genreIds,
                genreIds.stream().findFirst().orElse(null),
                new BigDecimal(voteAverage),
                voteCount,
                lang,
                "overview");
    }

    @Test
    void collect_fromLists_joinsWatchlistAggregateWithCatalogData() {
        Instant addedAt = Instant.parse("2026-01-01T00:00:00Z");
        DeckRequest request = requestOf(2, false);
        when(watchlistQueries.aggregateFor(request.memberIds()))
                .thenReturn(Map.of(603L, new WatchlistAgg(603L, 2, addedAt, 1)));
        when(catalogFacade.getCached(Set.of(603L), ContentLang.RU))
                .thenReturn(Map.of(603L, movieDto(603L, Set.of(28), 120, "7.5", 200, "en")));

        List<Candidate> result = collector.collect(request, Set.of());

        assertThat(result).hasSize(1);
        Candidate c = result.get(0);
        assertThat(c.tmdbId()).isEqualTo(603L);
        assertThat(c.ownersCount()).isEqualTo(2);
        assertThat(c.seenCount()).isEqualTo(1);
        assertThat(c.medianAddedAt()).isEqualTo(addedAt);
        assertThat(c.genreIds()).containsExactly(28);
        assertThat(c.runtimeMin()).isEqualTo(120);
        assertThat(c.voteAverage()).isEqualByComparingTo("7.5");
        assertThat(c.voteCount()).isEqualTo(200);
        assertThat(c.originalLanguage()).isEqualTo("en");
        assertThat(c.fromFallback()).isFalse();
    }

    @Test
    void collect_fewCandidates_watchlistOnlyFalse_triggersFallback() {
        DeckRequest request = requestOf(2, false); // threshold = 6
        when(watchlistQueries.aggregateFor(request.memberIds()))
                .thenReturn(Map.of(1L, new WatchlistAgg(1L, 1, Instant.now(), 0)));
        when(catalogFacade.popularByGenres(eq(Set.of(28)), eq(5), eq(ContentLang.RU)))
                .thenReturn(List.of(
                        movieDto(2L, Set.of(28), null, "6", 50, "fr"), movieDto(3L, Set.of(28), null, "6", 50, "fr")));
        when(catalogFacade.getCached(Set.of(1L, 2L, 3L), ContentLang.RU))
                .thenReturn(Map.of(
                        1L, movieDto(1L, Set.of(28), null, "6", 50, "fr"),
                        2L, movieDto(2L, Set.of(28), null, "6", 50, "fr"),
                        3L, movieDto(3L, Set.of(28), null, "6", 50, "fr")));

        List<Candidate> result = collector.collect(request, Set.of(28));

        assertThat(result).hasSize(3);
        assertThat(result.stream().filter(Candidate::fromFallback)).hasSize(2);
        assertThat(result.stream().filter(c -> !c.fromFallback())).hasSize(1);
    }

    @Test
    void collect_watchlistOnlyTrue_neverCallsFallbackEvenWhenStarved() {
        DeckRequest request = requestOf(2, true); // threshold = 6, but flag suppresses fallback
        when(watchlistQueries.aggregateFor(request.memberIds()))
                .thenReturn(Map.of(1L, new WatchlistAgg(1L, 1, Instant.now(), 0)));
        when(catalogFacade.getCached(Set.of(1L), ContentLang.RU))
                .thenReturn(Map.of(1L, movieDto(1L, Set.of(28), null, "6", 50, "fr")));

        List<Candidate> result = collector.collect(request, Set.of(28));

        verify(catalogFacade, never()).popularByGenres(any(), anyInt(), any());
        assertThat(result).hasSize(1);
    }

    @Test
    void collect_enoughCandidatesAlready_fallbackSkippedEvenWithFlagFalse() {
        DeckRequest request = requestOf(2, false); // threshold = 6
        Map<Long, WatchlistAgg> agg = Map.of(
                1L, new WatchlistAgg(1L, 1, Instant.now(), 0),
                2L, new WatchlistAgg(2L, 1, Instant.now(), 0),
                3L, new WatchlistAgg(3L, 1, Instant.now(), 0),
                4L, new WatchlistAgg(4L, 1, Instant.now(), 0),
                5L, new WatchlistAgg(5L, 1, Instant.now(), 0),
                6L, new WatchlistAgg(6L, 1, Instant.now(), 0));
        when(watchlistQueries.aggregateFor(request.memberIds())).thenReturn(agg);
        Map<Long, MovieDto> catalogData = agg.keySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        id -> id, id -> movieDto(id, Set.of(28), null, "6", 50, "fr")));
        when(catalogFacade.getCached(agg.keySet(), ContentLang.RU)).thenReturn(catalogData);

        List<Candidate> result = collector.collect(request, Set.of(28));

        verify(catalogFacade, never()).popularByGenres(any(), anyInt(), any());
        assertThat(result).hasSize(6);
    }

    @Test
    void collect_fallbackResultCoincidesWithBaseCandidate_isNotDuplicated() {
        DeckRequest request = requestOf(2, false);
        when(watchlistQueries.aggregateFor(request.memberIds()))
                .thenReturn(Map.of(1L, new WatchlistAgg(1L, 1, Instant.now(), 0)));
        when(catalogFacade.popularByGenres(eq(Set.of(28)), eq(5), eq(ContentLang.RU)))
                .thenReturn(List.of(
                        movieDto(1L, Set.of(28), null, "6", 50, "fr"), movieDto(2L, Set.of(28), null, "6", 50, "fr")));
        when(catalogFacade.getCached(Set.of(1L, 2L), ContentLang.RU))
                .thenReturn(Map.of(
                        1L, movieDto(1L, Set.of(28), null, "6", 50, "fr"),
                        2L, movieDto(2L, Set.of(28), null, "6", 50, "fr")));

        List<Candidate> result = collector.collect(request, Set.of(28));

        assertThat(result).extracting(Candidate::tmdbId).containsExactlyInAnyOrder(1L, 2L);
        Candidate one =
                result.stream().filter(c -> c.tmdbId() == 1L).findFirst().orElseThrow();
        assertThat(one.fromFallback()).isFalse();
    }

    @Test
    void collect_pastWinnerWithExactMemberComposition_excludedFromResult() {
        DeckRequest request = requestOf(2, false);
        when(watchlistQueries.aggregateFor(request.memberIds()))
                .thenReturn(Map.of(
                        1L, new WatchlistAgg(1L, 1, Instant.now(), 0),
                        2L, new WatchlistAgg(2L, 1, Instant.now(), 0)));
        when(catalogFacade.getCached(Set.of(1L, 2L), ContentLang.RU))
                .thenReturn(Map.of(
                        1L, movieDto(1L, Set.of(28), null, "6", 50, "fr"),
                        2L, movieDto(2L, Set.of(28), null, "6", 50, "fr")));
        when(sessionHistoryQueries.pastWinnersForExactMemberSet(Set.copyOf(request.memberIds())))
                .thenReturn(Set.of(1L));

        List<Candidate> result = collector.collect(request, Set.of());

        assertThat(result).extracting(Candidate::tmdbId).containsExactly(2L);
    }

    @Test
    void collect_everyoneHasEmptyLists_fallbackAloneFillsCandidates() {
        DeckRequest request = requestOf(2, false);
        when(watchlistQueries.aggregateFor(request.memberIds())).thenReturn(Map.of());
        when(catalogFacade.popularByGenres(eq(Set.of(28)), eq(6), eq(ContentLang.RU)))
                .thenReturn(List.of(
                        movieDto(1L, Set.of(28), null, "6", 50, "fr"), movieDto(2L, Set.of(28), null, "6", 50, "fr")));
        when(catalogFacade.getCached(Set.of(1L, 2L), ContentLang.RU))
                .thenReturn(Map.of(
                        1L, movieDto(1L, Set.of(28), null, "6", 50, "fr"),
                        2L, movieDto(2L, Set.of(28), null, "6", 50, "fr")));

        List<Candidate> result = collector.collect(request, Set.of(28));

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(Candidate::fromFallback);
    }

    @Test
    void collect_catalogFetchedExactlyOnce_withTheFullCombinedIdSet() {
        DeckRequest request = requestOf(2, false);
        when(watchlistQueries.aggregateFor(request.memberIds()))
                .thenReturn(Map.of(1L, new WatchlistAgg(1L, 1, Instant.now(), 0)));
        when(catalogFacade.popularByGenres(eq(Set.of(28)), eq(5), eq(ContentLang.RU)))
                .thenReturn(List.of(movieDto(2L, Set.of(28), null, "6", 50, "fr")));
        when(catalogFacade.getCached(Set.of(1L, 2L), ContentLang.RU))
                .thenReturn(Map.of(
                        1L, movieDto(1L, Set.of(28), null, "6", 50, "fr"),
                        2L, movieDto(2L, Set.of(28), null, "6", 50, "fr")));

        collector.collect(request, Set.of(28));

        verify(catalogFacade, times(1)).getCached(Set.of(1L, 2L), ContentLang.RU);
    }

    @Test
    void collect_emptyUnionFavGenres_skipsFallbackCallEvenWhenNeeded() {
        DeckRequest request = requestOf(2, false);
        when(watchlistQueries.aggregateFor(request.memberIds()))
                .thenReturn(Map.of(1L, new WatchlistAgg(1L, 1, Instant.now(), 0)));
        when(catalogFacade.getCached(Set.of(1L), ContentLang.RU))
                .thenReturn(Map.of(1L, movieDto(1L, Set.of(28), null, "6", 50, "fr")));

        List<Candidate> result = collector.collect(request, Set.of());

        verify(catalogFacade, never()).popularByGenres(any(), anyInt(), any());
        assertThat(result).hasSize(1);
    }
}
