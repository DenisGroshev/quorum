package app.quorum.matching.domain;

import static app.quorum.matching.domain.CandidateFixture.candidate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import app.quorum.matching.config.MatchingProperties;
import app.quorum.matching.config.MatchingProperties.Penalties;
import app.quorum.matching.config.MatchingProperties.Weights;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MovieScorerTest {

    private static final MatchingProperties PROPERTIES = new MatchingProperties(
            "w1", new Weights(45, 20, 15, 10, 10), new Penalties(25, 15, 10), 150, 180, 100, 0.3, 0.4, 3, 0.5);

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    private final MovieScorer scorer = new MovieScorer(PROPERTIES);

    private static ScoringContext contextOf(int participantCount, List<Set<Integer>> favGenres, Set<String> langs) {
        return new ScoringContext(participantCount, favGenres, langs, NOW);
    }

    private static ScoringContext contextWithNParticipants(int n) {
        return contextOf(n, List.of(), Set.of());
    }

    @Test
    void overlap_fourParticipantsOwnedByThree_isThreeQuarters() {
        Candidate c = candidate().ownersCount(3).build();

        assertThat(scorer.overlapScore(c, contextWithNParticipants(4))).isEqualTo(0.75);
    }

    @Test
    void overlap_ownedByEveryone_isOne() {
        Candidate c = candidate().ownersCount(4).build();

        assertThat(scorer.overlapScore(c, contextWithNParticipants(4))).isEqualTo(1.0);
    }

    @Test
    void overlap_zeroParticipants_isZeroNotDivideByZero() {
        Candidate c = candidate().ownersCount(0).build();

        assertThat(scorer.overlapScore(c, contextWithNParticipants(0))).isEqualTo(0.0);
    }

    @Test
    void score_weightsOverlapByFortyFive_matchesWorkedExample() {
        Candidate c = candidate()
                .ownersCount(3)
                .seenCount(0)
                .medianAddedAt(NOW)
                .originalLanguage(null)
                .build();
        ScoringContext ctx = contextWithNParticipants(4);

        double expectedOverlapContribution = 45 * 0.75;

        assertThat(scorer.overlapScore(c, ctx) * PROPERTIES.weights().overlap()).isEqualTo(expectedOverlapContribution);
    }

    @Test
    void genreFit_identicalGenreSets_isOne() {
        Candidate c = candidate().genreIds(Set.of(28, 12)).build();
        ScoringContext ctx = contextOf(1, List.of(Set.of(28, 12)), Set.of());

        assertThat(scorer.genreFitScore(c, ctx)).isEqualTo(1.0);
    }

    @Test
    void genreFit_noOverlapAtAll_isZero() {
        Candidate c = candidate().genreIds(Set.of(28)).build();
        ScoringContext ctx = contextOf(1, List.of(Set.of(18)), Set.of());

        assertThat(scorer.genreFitScore(c, ctx)).isEqualTo(0.0);
    }

    @Test
    void genreFit_jaccardWorkedExample_actionSciFiVsSciFiDrama() {
        Candidate c = candidate().genreIds(Set.of(28, 878)).build();
        ScoringContext ctx = contextOf(1, List.of(Set.of(878, 18)), Set.of());

        assertThat(scorer.genreFitScore(c, ctx)).isCloseTo(1.0 / 3.0, within(1e-9));
    }

    @Test
    void genreFit_participantWithNoFavoriteGenres_scoresZeroButStillCountsInAverage() {
        Candidate c = candidate().genreIds(Set.of(28)).build();
        ScoringContext ctx = contextOf(2, List.of(Set.of(28), Set.of()), Set.of());

        assertThat(scorer.genreFitScore(c, ctx)).isEqualTo(0.5);
    }

    @Test
    void genreFit_movieWithNoGenres_isZero() {
        Candidate c = candidate().genreIds(Set.of()).build();
        ScoringContext ctx = contextOf(1, List.of(Set.of(28)), Set.of());

        assertThat(scorer.genreFitScore(c, ctx)).isEqualTo(0.0);
    }

    @Test
    void genreFit_bothMovieAndParticipantHaveNoGenres_isZeroNotNaN() {
        Candidate c = candidate().genreIds(Set.of()).build();
        ScoringContext ctx = contextOf(1, List.of(Set.of()), Set.of());

        assertThat(scorer.genreFitScore(c, ctx)).isEqualTo(0.0);
    }

    @Test
    void genreFit_zeroParticipants_isZero() {
        Candidate c = candidate().genreIds(Set.of(28)).build();

        assertThat(scorer.genreFitScore(c, contextOf(0, List.of(), Set.of()))).isEqualTo(0.0);
    }

    @Test
    void rating_highScoreEnoughVotes_clampsToOne() {
        Candidate c =
                candidate().voteAverage(new BigDecimal("8")).voteCount(500).build();

        assertThat(scorer.ratingScore(c)).isEqualTo(1.0);
    }

    @Test
    void rating_midScoreEnoughVotes_stretchesRangeCorrectly() {
        Candidate c =
                candidate().voteAverage(new BigDecimal("6.5")).voteCount(500).build();

        assertThat(scorer.ratingScore(c)).isEqualTo(0.5);
    }

    @Test
    void rating_tooFewVotes_usesDefaultRegardlessOfScore() {
        Candidate c =
                candidate().voteAverage(new BigDecimal("9.5")).voteCount(50).build();

        assertThat(scorer.ratingScore(c)).isEqualTo(0.3);
    }

    @Test
    void rating_belowFive_clampsToZero() {
        Candidate c =
                candidate().voteAverage(new BigDecimal("4")).voteCount(500).build();

        assertThat(scorer.ratingScore(c)).isEqualTo(0.0);
    }

    @Test
    void recency_addedToday_isOne() {
        Candidate c = candidate().medianAddedAt(NOW).build();

        assertThat(scorer.recencyScore(c, NOW)).isEqualTo(1.0);
    }

    @Test
    void recency_exactlyAtHorizon_isZero() {
        Candidate c = candidate().medianAddedAt(NOW.minus(180, ChronoUnit.DAYS)).build();

        assertThat(scorer.recencyScore(c, NOW)).isEqualTo(0.0);
    }

    @Test
    void recency_wellPastHorizon_clampsToZeroNotNegative() {
        Candidate c = candidate().medianAddedAt(NOW.minus(400, ChronoUnit.DAYS)).build();

        assertThat(scorer.recencyScore(c, NOW)).isEqualTo(0.0);
    }

    @Test
    void recency_halfwayToHorizon_isHalf() {
        Candidate c = candidate().medianAddedAt(NOW.minus(90, ChronoUnit.DAYS)).build();

        assertThat(scorer.recencyScore(c, NOW)).isEqualTo(0.5);
    }

    @Test
    void recency_noMedianDate_isZero() {
        Candidate c = candidate().medianAddedAt(null).build();

        assertThat(scorer.recencyScore(c, NOW)).isEqualTo(0.0);
    }

    @Test
    void availability_isAlwaysTheConfiguredStub() {
        assertThat(scorer.availabilityScore()).isEqualTo(0.5);
    }

    @Test
    void seenShare_halfOfParticipantsHaveSeenIt_isOneHalf() {
        Candidate c = candidate().seenCount(2).build();

        assertThat(scorer.seenShareFraction(c, contextWithNParticipants(4))).isEqualTo(0.5);
    }

    @Test
    void seenShare_zeroParticipants_isZero() {
        Candidate c = candidate().seenCount(0).build();

        assertThat(scorer.seenShareFraction(c, contextWithNParticipants(0))).isEqualTo(0.0);
    }

    @Test
    void longRuntime_aboveThreshold_indicatorIsOne() {
        Candidate c = candidate().runtimeMin(180).build();

        assertThat(scorer.longRuntimePenaltyIndicator(c)).isEqualTo(1.0);
    }

    @Test
    void longRuntime_belowThreshold_indicatorIsZero() {
        Candidate c = candidate().runtimeMin(100).build();

        assertThat(scorer.longRuntimePenaltyIndicator(c)).isEqualTo(0.0);
    }

    @Test
    void longRuntime_unknownRuntime_indicatorIsZeroNotPenalized() {
        Candidate c = candidate().runtimeMin(null).build();

        assertThat(scorer.longRuntimePenaltyIndicator(c)).isEqualTo(0.0);
    }

    @Test
    void foreignLowRated_foreignAndLowRated_indicatorIsOne() {
        Candidate c = candidate()
                .originalLanguage("en")
                .voteAverage(new BigDecimal("6"))
                .voteCount(500)
                .build();

        assertThat(scorer.foreignLowRatedPenaltyIndicator(c, Set.of("ru"))).isEqualTo(1.0);
    }

    @Test
    void foreignLowRated_foreignButHighlyRated_indicatorIsZero() {
        Candidate c = candidate()
                .originalLanguage("en")
                .voteAverage(new BigDecimal("8"))
                .voteCount(500)
                .build();

        assertThat(scorer.foreignLowRatedPenaltyIndicator(c, Set.of("ru"))).isEqualTo(0.0);
    }

    @Test
    void foreignLowRated_languageMatchesAParticipant_indicatorIsZeroEvenIfLowRated() {
        Candidate c = candidate()
                .originalLanguage("ru")
                .voteAverage(new BigDecimal("6"))
                .voteCount(500)
                .build();

        assertThat(scorer.foreignLowRatedPenaltyIndicator(c, Set.of("ru"))).isEqualTo(0.0);
    }

    @Test
    void foreignLowRated_unknownLanguage_indicatorIsZero() {
        Candidate c = candidate()
                .originalLanguage(null)
                .voteAverage(new BigDecimal("6"))
                .voteCount(500)
                .build();

        assertThat(scorer.foreignLowRatedPenaltyIndicator(c, Set.of("ru"))).isEqualTo(0.0);
    }

    @Test
    void score_everyOptionalFieldMissing_computesWithoutThrowing() {
        Candidate c = new Candidate(1L, 0, 0, null, null, null, null, BigDecimal.ZERO, 0, null, false);
        ScoringContext ctx = contextOf(0, List.of(), Set.of());

        BigDecimal score = scorer.score(c, ctx);

        assertThat(score).isNotNull();
    }

    @Test
    void score_sameInputTwice_producesTheSameResult() {
        Candidate c = candidate()
                .ownersCount(2)
                .genreIds(Set.of(28))
                .voteAverage(new BigDecimal("7"))
                .voteCount(500)
                .medianAddedAt(NOW.minus(30, ChronoUnit.DAYS))
                .runtimeMin(120)
                .originalLanguage("en")
                .build();
        ScoringContext ctx = contextOf(3, List.of(Set.of(28), Set.of(), Set.of(12)), Set.of("ru"));

        BigDecimal first = scorer.score(c, ctx);
        BigDecimal second = scorer.score(c, ctx);

        assertThat(first).isEqualByComparingTo(second);
    }

    @Test
    void score_combinesAllEightWeightedTerms_matchesHandComputedTotal() {
        Candidate c = new Candidate(
                1L,
                3,
                2,
                NOW.minus(90, ChronoUnit.DAYS), // recency = 0.5
                Set.of(28, 878),
                878,
                180,
                new BigDecimal("6"),
                500,
                "en",
                false);
        ScoringContext ctx = contextOf(4, List.of(Set.of(878, 18)), Set.of("ru"));

        double overlap = 3.0 / 4.0; // 0.75
        double genreFit = 1.0 / 3.0; // {28,878} vs {878,18}
        double rating = (6.0 - 5.0) / 3.0; // 0.333...
        double recency = 0.5;
        double availability = 0.5;
        double seenShare = 2.0 / 4.0; // 0.5
        double longRuntime = 1.0;
        double foreignLowRated = 1.0; // "en" not in {"ru"}, rating 6 < 7

        double expected = 45 * overlap
                + 20 * genreFit
                + 15 * rating
                + 10 * recency
                + 10 * availability
                - 25 * seenShare
                - 15 * longRuntime
                - 10 * foreignLowRated;

        assertThat(scorer.score(c, ctx).doubleValue()).isCloseTo(expected, within(0.01));
    }
}
