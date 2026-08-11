package app.quorum.matching.domain;

import static app.quorum.matching.domain.CandidateFixture.candidate;
import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.matching.config.MatchingProperties;
import app.quorum.matching.config.MatchingProperties.Penalties;
import app.quorum.matching.config.MatchingProperties.Weights;
import app.quorum.session.domain.DeckReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReasonBuilderTest {

    private static final MatchingProperties PROPERTIES = new MatchingProperties(
            "w1", new Weights(45, 20, 15, 10, 10), new Penalties(25, 15, 10), 150, 180, 100, 0.3, 0.4, 3, 0.5);

    private final ReasonBuilder reasonBuilder = new ReasonBuilder(PROPERTIES);

    private static ScoringContext contextOf(int participantCount, List<Set<Integer>> favGenres) {
        return new ScoringContext(participantCount, favGenres, Set.of(), Instant.now());
    }

    @Test
    void inWatchlist_addsReasonWithCountAndTotal() {
        Candidate c = candidate().ownersCount(3).build();

        List<DeckReason> reasons = reasonBuilder.buildReasons(c, contextOf(4, List.of()), Map.of());

        assertThat(reasons).extracting(DeckReason::code).contains("IN_WATCHLIST_OF_N");
        DeckReason reason = reasons.stream()
                .filter(r -> r.code().equals("IN_WATCHLIST_OF_N"))
                .findFirst()
                .orElseThrow();
        assertThat(reason.args()).containsEntry("count", 3).containsEntry("total", 4);
    }

    @Test
    void notInAnyList_noWatchlistReason() {
        Candidate c = candidate().ownersCount(0).fromFallback(true).build();

        List<DeckReason> reasons = reasonBuilder.buildReasons(c, contextOf(4, List.of()), Map.of());

        assertThat(reasons).extracting(DeckReason::code).doesNotContain("IN_WATCHLIST_OF_N");
    }

    @Test
    void genreMatch_addsReasonWithMatchedGenreNames() {
        Candidate c = candidate().genreIds(Set.of(28, 878)).build();
        ScoringContext ctx = contextOf(2, List.of(Set.of(878), Set.of()));
        Map<Integer, String> genreNames = Map.of(878, "Фантастика", 28, "Боевик");

        List<DeckReason> reasons = reasonBuilder.buildReasons(c, ctx, genreNames);

        DeckReason reason = reasons.stream()
                .filter(r -> r.code().equals("GENRE_MATCH"))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> names = (List<String>) reason.args().get("genres");
        assertThat(names).containsExactly("Фантастика");
    }

    @Test
    void noGenreOverlapWithAnyParticipant_noGenreMatchReason() {
        Candidate c = candidate().genreIds(Set.of(28)).build();
        ScoringContext ctx = contextOf(1, List.of(Set.of(18)));

        List<DeckReason> reasons = reasonBuilder.buildReasons(c, ctx, Map.of());

        assertThat(reasons).extracting(DeckReason::code).doesNotContain("GENRE_MATCH");
    }

    @Test
    void highRating_aboveThresholdWithEnoughVotes_addsReasonWithValue() {
        Candidate c =
                candidate().voteAverage(new BigDecimal("8.2")).voteCount(500).build();

        List<DeckReason> reasons = reasonBuilder.buildReasons(c, contextOf(1, List.of()), Map.of());

        DeckReason reason = reasons.stream()
                .filter(r -> r.code().equals("HIGH_RATING"))
                .findFirst()
                .orElseThrow();
        assertThat(reason.args()).containsEntry("value", new BigDecimal("8.2"));
    }

    @Test
    void highRating_notEnoughVotes_noReasonEvenIfScoreIsHigh() {
        Candidate c =
                candidate().voteAverage(new BigDecimal("9.5")).voteCount(10).build();

        List<DeckReason> reasons = reasonBuilder.buildReasons(c, contextOf(1, List.of()), Map.of());

        assertThat(reasons).extracting(DeckReason::code).doesNotContain("HIGH_RATING");
    }

    @Test
    void highRating_belowThreshold_noReason() {
        Candidate c =
                candidate().voteAverage(new BigDecimal("7.0")).voteCount(500).build();

        List<DeckReason> reasons = reasonBuilder.buildReasons(c, contextOf(1, List.of()), Map.of());

        assertThat(reasons).extracting(DeckReason::code).doesNotContain("HIGH_RATING");
    }

    @Test
    void fromFallback_addsPopularFallbackReason() {
        Candidate c = candidate().fromFallback(true).build();

        List<DeckReason> reasons = reasonBuilder.buildReasons(c, contextOf(1, List.of()), Map.of());

        assertThat(reasons).extracting(DeckReason::code).contains("POPULAR_FALLBACK");
    }

    @Test
    void notFromFallback_noPopularFallbackReason() {
        Candidate c = candidate().ownersCount(1).fromFallback(false).build();

        List<DeckReason> reasons = reasonBuilder.buildReasons(c, contextOf(1, List.of()), Map.of());

        assertThat(reasons).extracting(DeckReason::code).doesNotContain("POPULAR_FALLBACK");
    }

    @Test
    void priorityOrder_watchlistBeatsGenreBeatsRating_whenAllApply() {
        Candidate c = candidate()
                .ownersCount(2)
                .genreIds(Set.of(28))
                .voteAverage(new BigDecimal("8.5"))
                .voteCount(500)
                .build();
        ScoringContext ctx = contextOf(2, List.of(Set.of(28)));

        List<DeckReason> reasons = reasonBuilder.buildReasons(c, ctx, Map.of(28, "Боевик"));

        assertThat(reasons)
                .extracting(DeckReason::code)
                .containsExactly("IN_WATCHLIST_OF_N", "GENRE_MATCH", "HIGH_RATING");
    }

    @Test
    void everyDeckMovie_getsAtLeastOneReason() {
        Candidate listSourced = candidate().ownersCount(1).build();
        Candidate fallbackSourced = candidate().fromFallback(true).build();

        assertThat(reasonBuilder.buildReasons(listSourced, contextOf(1, List.of()), Map.of()))
                .isNotEmpty();
        assertThat(reasonBuilder.buildReasons(fallbackSourced, contextOf(1, List.of()), Map.of()))
                .isNotEmpty();
    }
}
