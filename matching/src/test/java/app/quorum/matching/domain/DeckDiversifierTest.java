package app.quorum.matching.domain;

import static app.quorum.matching.domain.CandidateFixture.candidate;
import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.matching.config.MatchingProperties;
import app.quorum.matching.config.MatchingProperties.Penalties;
import app.quorum.matching.config.MatchingProperties.Weights;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DeckDiversifierTest {

    private static final MatchingProperties PROPERTIES = new MatchingProperties(
            "w1", new Weights(45, 20, 15, 10, 10), new Penalties(25, 15, 10), 150, 180, 100, 0.3, 0.4, 3, 0.5);

    private final DeckDiversifier diversifier = new DeckDiversifier(PROPERTIES);

    private static ScoredCandidate scored(long tmdbId, Integer primaryGenreId) {
        Candidate c = candidate()
                .tmdbId(tmdbId)
                .primaryGenreId(primaryGenreId)
                .genreIds(primaryGenreId != null ? Set.of(primaryGenreId) : Set.of())
                .build();
        return new ScoredCandidate(c, BigDecimal.ONE);
    }

    @Test
    void thirtyCandidatesTwentyFiveOfOneGenre_capsThatGenreAtTheConfiguredLimit() {
        List<ScoredCandidate> input = new ArrayList<>();
        IntStream.rangeClosed(1, 25).forEach(i -> input.add(scored(i, 1)));
        IntStream.rangeClosed(1, 15).forEach(i -> input.add(scored(100 + i, 1 + i)));

        List<ScoredCandidate> deck = diversifier.diversify(input, 20);

        assertThat(deck).hasSize(20);
        long genreOneCount = deck.stream()
                .filter(sc -> Integer.valueOf(1).equals(sc.candidate().primaryGenreId()))
                .count();
        assertThat(genreOneCount).isLessThanOrEqualTo(8);
    }

    @Test
    void notEnoughAlternativeGenres_backfillsPastTheLimitRatherThanShrinkingTheDeck() {
        List<ScoredCandidate> input =
                IntStream.rangeClosed(1, 10).mapToObj(i -> scored(i, 1)).toList();

        List<ScoredCandidate> deck = diversifier.diversify(input, 10);

        assertThat(deck).hasSize(10);
        assertThat(deck).allMatch(sc -> Integer.valueOf(1).equals(sc.candidate().primaryGenreId()));
    }

    @Test
    void candidatesWithoutAPrimaryGenre_areNeverCapped() {
        List<ScoredCandidate> input = new ArrayList<>();
        IntStream.rangeClosed(1, 8).forEach(i -> input.add(scored(i, 1)));
        IntStream.rangeClosed(1, 5).forEach(i -> input.add(scored(200 + i, null)));

        List<ScoredCandidate> deck = diversifier.diversify(input, 13);

        assertThat(deck).hasSize(13);
        long noGenreCount = deck.stream()
                .filter(sc -> sc.candidate().primaryGenreId() == null)
                .count();
        assertThat(noGenreCount).isEqualTo(5);
    }

    @Test
    void fewerCandidatesThanDeckSize_returnsWhateverIsAvailableWithoutError() {
        List<ScoredCandidate> input = List.of(scored(1, 1), scored(2, 2));

        List<ScoredCandidate> deck = diversifier.diversify(input, 20);

        assertThat(deck).hasSize(2);
    }

    @Test
    void respectsTheGivenOrder_higherScoredCandidatesPreferredWithinTheSameGenre() {
        List<ScoredCandidate> input =
                IntStream.rangeClosed(1, 10).mapToObj(i -> scored(i, 1)).toList();

        List<ScoredCandidate> deck =
                diversifier.diversify(input, 3); // limit = floor(3*0.4) = 1... but backfill applies


        assertThat(deck).extracting(sc -> sc.candidate().tmdbId()).containsExactly(1L, 2L, 3L);
    }
}
