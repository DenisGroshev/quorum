package app.quorum.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.quorum.catalog.api.CatalogFacade;
import app.quorum.catalog.api.MovieDto;
import app.quorum.common.ContentLang;
import app.quorum.matching.config.MatchingProperties;
import app.quorum.matching.config.MatchingProperties.Penalties;
import app.quorum.matching.config.MatchingProperties.Weights;
import app.quorum.matching.infra.GenreNameRepository;
import app.quorum.session.api.SessionHistoryQueries;
import app.quorum.session.domain.DeckRequest;
import app.quorum.session.domain.DeckResult;
import app.quorum.session.domain.ScoredMovie;
import app.quorum.user.api.UserQueries;
import app.quorum.watchlist.api.WatchlistAgg;
import app.quorum.watchlist.api.WatchlistQueries;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HeuristicDeckBuilderTest {

    private static final MatchingProperties PROPERTIES = new MatchingProperties(
            "w7", new Weights(45, 20, 15, 10, 10), new Penalties(25, 15, 10), 150, 180, 100, 0.3, 0.4, 3, 0.5);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private CandidateCollector candidateCollector;

    @Mock
    private MovieScorer movieScorer;

    @Mock
    private DeckDiversifier deckDiversifier;

    @Mock
    private ReasonBuilder reasonBuilder;

    @Mock
    private GenreNameRepository genreNameRepository;

    @Mock
    private UserQueries userQueries;

    private HeuristicDeckBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new HeuristicDeckBuilder(
                candidateCollector,
                movieScorer,
                deckDiversifier,
                reasonBuilder,
                genreNameRepository,
                userQueries,
                PROPERTIES,
                CLOCK);
        lenient().when(userQueries.favGenres(any())).thenReturn(Map.of());
        lenient().when(userQueries.contentLangs(any())).thenReturn(Map.of());
        lenient().when(genreNameRepository.namesRu(anySet())).thenReturn(Map.of());
        lenient().when(reasonBuilder.buildReasons(any(), any(), any())).thenReturn(List.of());
    }

    private static DeckRequest requestOf(UUID sessionId, int memberCount, int deckSize) {
        List<UUID> members =
                IntStream.range(0, memberCount).mapToObj(i -> UUID.randomUUID()).toList();
        return new DeckRequest(sessionId, members, deckSize, false);
    }

    private static Candidate candidateWithId(long tmdbId) {
        return new Candidate(tmdbId, 0, 0, null, Set.of(), null, null, BigDecimal.ZERO, 0, null, false);
    }

    @Test
    void sameSessionId_twiceInARow_producesTheSameOrder() {
        UUID sessionId = UUID.randomUUID();
        stubDeterministicScoring(10);
        DeckRequest request = requestOf(sessionId, 1, 10);

        DeckResult first = builder.build(request);
        DeckResult second = builder.build(request);

        assertThat(idsOf(first)).isEqualTo(idsOf(second));
    }

    @Test
    void differentSessionIds_produceDifferentOrder() {
        stubDeterministicScoring(10);

        DeckResult a = builder.build(requestOf(UUID.randomUUID(), 1, 10));
        DeckResult b = builder.build(requestOf(UUID.randomUUID(), 1, 10));

        assertThat(idsOf(a)).isNotEqualTo(idsOf(b));
    }

    @Test
    void deckOrder_isShuffled_notLeftInScoreDescendingOrder() {
        stubDeterministicScoring(10);

        DeckResult result = builder.build(requestOf(UUID.randomUUID(), 1, 10));

        List<Long> scoreDescendingOrder =
                IntStream.rangeClosed(1, 10).mapToObj(i -> (long) i).toList();
        assertThat(idsOf(result)).isNotEqualTo(scoreDescendingOrder);
    }

    @Test
    void configVersion_isStampedFromMatchingProperties() {
        stubDeterministicScoring(3);

        DeckResult result = builder.build(requestOf(UUID.randomUUID(), 1, 3));

        assertThat(result.configVersion()).isEqualTo("w7");
    }

    @Test
    void emptyCandidates_returnsEmptyDeckWithoutThrowing() {
        when(candidateCollector.collect(any(), anySet())).thenReturn(List.of());

        DeckResult result = builder.build(requestOf(UUID.randomUUID(), 1, 20));

        assertThat(result.movies()).isEmpty();
        assertThat(result.configVersion()).isEqualTo("w7");
    }

    @Test
    void reasonsAreBuiltOnlyOnceEachForFinalDeckMembers_notThePreDiversifiedPool() {
        // 50 raw candidates but a deck size of 5 - reasonBuilder must be called exactly 5 times,
        // proving reasons aren't built for the whole candidate pool before diversification/shuffle.
        stubCandidatesWithDescendingScores(50);
        when(deckDiversifier.diversify(any(), eq(5))).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).subList(0, 5));

        builder.build(requestOf(UUID.randomUUID(), 1, 5));

        verify(reasonBuilder, times(5)).buildReasons(any(), any(), any());
    }

    /** Real MovieScorer + real per-term math, only the I/O boundary is mocked - proves the whole non-mocked pipeline end to end. */
    @Test
    void endToEnd_movieEveryoneHasInTheirList_scoresHighestOfAllCandidates() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        DeckRequest request = new DeckRequest(sessionId, List.of(alice, bob), 10, false);

        WatchlistQueries watchlistQueries = mock(WatchlistQueries.class);
        CatalogFacade catalogFacade = mock(CatalogFacade.class);
        SessionHistoryQueries sessionHistoryQueries = mock(SessionHistoryQueries.class);
        UserQueries realishUserQueries = mock(UserQueries.class);
        GenreNameRepository realishGenreNames = mock(GenreNameRepository.class);

        Instant addedAt = CLOCK.instant();
        when(watchlistQueries.aggregateFor(request.memberIds()))
                .thenReturn(Map.of(
                        1L, new WatchlistAgg(1L, 2, addedAt, 0),
                        2L, new WatchlistAgg(2L, 1, addedAt, 0)));
        when(catalogFacade.getCached(Set.of(1L, 2L), ContentLang.RU))
                .thenReturn(Map.of(
                        1L, movieDto(1L),
                        2L, movieDto(2L)));
        when(sessionHistoryQueries.pastWinnersForExactMemberSet(Set.of(alice, bob)))
                .thenReturn(Set.of());
        when(realishUserQueries.favGenres(request.memberIds())).thenReturn(Map.of());
        when(realishUserQueries.contentLangs(request.memberIds())).thenReturn(Map.of());
        when(realishGenreNames.namesRu(anySet())).thenReturn(Map.of());

        CandidateCollector realCollector =
                new CandidateCollector(watchlistQueries, catalogFacade, sessionHistoryQueries, PROPERTIES);
        MovieScorer realScorer = new MovieScorer(PROPERTIES);
        DeckDiversifier realDiversifier = new DeckDiversifier(PROPERTIES);
        ReasonBuilder realReasonBuilder = new ReasonBuilder(PROPERTIES);
        HeuristicDeckBuilder realBuilder = new HeuristicDeckBuilder(
                realCollector,
                realScorer,
                realDiversifier,
                realReasonBuilder,
                realishGenreNames,
                realishUserQueries,
                PROPERTIES,
                CLOCK);

        DeckResult result = realBuilder.build(request);

        Map<Long, BigDecimal> scoresById = new HashMap<>();
        for (ScoredMovie m : result.movies()) {
            scoresById.put(m.tmdbId(), m.score());
        }
        assertThat(scoresById.get(1L)).isGreaterThan(scoresById.get(2L));
    }

    @Test
    void performance_tenMembersWithFiveHundredMoviesEach_buildsWellUnderOneAndAHalfSeconds() {
        int candidateCount = 5000;
        List<Candidate> candidates = IntStream.range(0, candidateCount)
                .mapToObj(i -> new Candidate(
                        i + 1,
                        i % 10,
                        i % 3,
                        CLOCK.instant(),
                        Set.of(i % 19 + 1),
                        i % 19 + 1,
                        90 + (i % 60),
                        new BigDecimal("7.0"),
                        150,
                        "en",
                        false))
                .toList();
        when(candidateCollector.collect(any(), anySet())).thenReturn(candidates);
        MovieScorer realScorer = new MovieScorer(PROPERTIES);
        DeckDiversifier realDiversifier = new DeckDiversifier(PROPERTIES);
        HeuristicDeckBuilder perfBuilder = new HeuristicDeckBuilder(
                candidateCollector,
                realScorer,
                realDiversifier,
                reasonBuilder,
                genreNameRepository,
                userQueries,
                PROPERTIES,
                CLOCK);
        DeckRequest request = requestOf(UUID.randomUUID(), 10, 20);

        long start = System.nanoTime();
        DeckResult result = perfBuilder.build(request);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.movies()).hasSize(20);
        assertThat(elapsedMs).isLessThan(1500);
    }

    private void stubDeterministicScoring(int n) {
        stubCandidatesWithDescendingScores(n);
        when(deckDiversifier.diversify(any(), eq(n))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubCandidatesWithDescendingScores(int n) {
        List<Candidate> candidates = IntStream.rangeClosed(1, n)
                .mapToObj(HeuristicDeckBuilderTest::candidateWithId)
                .toList();
        when(candidateCollector.collect(any(), anySet())).thenReturn(candidates);
        for (int i = 0; i < candidates.size(); i++) {
            when(movieScorer.score(eq(candidates.get(i)), any(ScoringContext.class)))
                    .thenReturn(BigDecimal.valueOf(n - i));
        }
    }

    private static List<Long> idsOf(DeckResult result) {
        return result.movies().stream().map(ScoredMovie::tmdbId).toList();
    }

    private static MovieDto movieDto(long tmdbId) {
        return new MovieDto(
                tmdbId,
                "Title",
                "Original",
                2020,
                100,
                "poster.jpg",
                Set.of(28),
                28,
                new BigDecimal("7"),
                500,
                "en",
                "overview");
    }
}
