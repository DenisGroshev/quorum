package app.quorum.voting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class WinnerCalculatorTest {

    private final WinnerCalculator calculator = new WinnerCalculator();

    private static WinnerCandidate candidate(
            long tmdbId, int points, int superlikes, int dislikes, double matchScore, Integer runtimeMin) {
        return new WinnerCandidate(
                new VoteTally(tmdbId, points, superlikes, dislikes), BigDecimal.valueOf(matchScore), runtimeMin);
    }

    @Test
    void noCandidates_returnsEmptyResult() {
        WinnerResult result = calculator.calculate(List.of(), 0, 0.6);

        assertThat(result.winnerTmdbId()).isNull();
        assertThat(result.contested()).isFalse();
        assertThat(result.top3()).isEmpty();
    }

    @Test
    void simpleCase_maxPointsWins() {
        WinnerCandidate a = candidate(101L, 10, 0, 0, 5.0, 100);
        WinnerCandidate b = candidate(102L, 5, 0, 0, 5.0, 100);

        WinnerResult result = calculator.calculate(List.of(a, b), 5, 0.6);

        assertThat(result.winnerTmdbId()).isEqualTo(101L);
        assertThat(result.contested()).isFalse();
    }

    @Test
    void veto_triggersAtExactly60Percent() {
        WinnerCandidate vetoed = candidate(201L, 10, 0, 3, 5.0, 100);
        WinnerCandidate safe = candidate(202L, 4, 0, 0, 5.0, 100);

        WinnerResult result = calculator.calculate(List.of(vetoed, safe), 5, 0.6);

        assertThat(result.winnerTmdbId()).isEqualTo(202L);
        assertThat(result.contested()).isFalse();
    }

    @Test
    void veto_doesNotTriggerAt50Percent() {
        WinnerCandidate highDislikes = candidate(203L, 10, 0, 2, 5.0, 100);
        WinnerCandidate lowerPoints = candidate(204L, 4, 0, 0, 5.0, 100);

        WinnerResult result = calculator.calculate(List.of(highDislikes, lowerPoints), 4, 0.6);

        assertThat(result.winnerTmdbId()).isEqualTo(203L);
    }

    @Test
    void veto_doesNotTriggerJustBelowThreshold() {
        WinnerCandidate justUnder = candidate(205L, 10, 0, 5, 5.0, 100);
        WinnerCandidate lowerPoints = candidate(206L, 4, 0, 0, 5.0, 100);

        WinnerResult result = calculator.calculate(List.of(justUnder, lowerPoints), 9, 0.6);

        assertThat(result.winnerTmdbId()).isEqualTo(205L);
    }

    @Test
    void veto_triggersJustAboveThreshold() {
        WinnerCandidate justOver = candidate(207L, 10, 0, 6, 5.0, 100);
        WinnerCandidate lowerPoints = candidate(208L, 4, 0, 0, 5.0, 100);

        WinnerResult result = calculator.calculate(List.of(justOver, lowerPoints), 9, 0.6);

        assertThat(result.winnerTmdbId()).isEqualTo(208L);
    }

    @Test
    void veto_allCandidatesVetoed_compromiseWinnerWithFlag() {
        WinnerCandidate a = candidate(301L, 10, 0, 3, 5.0, 100);
        WinnerCandidate b = candidate(302L, 6, 0, 3, 5.0, 100);

        WinnerResult result = calculator.calculate(List.of(a, b), 5, 0.6);

        assertThat(result.winnerTmdbId()).isEqualTo(301L);
        assertThat(result.contested()).isTrue();
    }

    @Test
    void veto_usesGivenVotersCountParameterNotSomeOtherDenominator() {
        WinnerCandidate a = candidate(303L, 10, 0, 3, 5.0, 100);
        WinnerCandidate b = candidate(304L, 4, 0, 0, 5.0, 100);

        WinnerResult result = calculator.calculate(List.of(a, b), 100, 0.6);

        assertThat(result.winnerTmdbId()).isEqualTo(303L);
        assertThat(result.contested()).isFalse();
    }

    @Test
    void tie_resolvedBySuperlikesDescending() {
        WinnerCandidate more = candidate(401L, 10, 2, 0, 5.0, 100);
        WinnerCandidate fewer = candidate(402L, 10, 1, 0, 5.0, 100);

        WinnerResult result = calculator.calculate(List.of(fewer, more), 5, 0.6);

        assertThat(result.winnerTmdbId()).isEqualTo(401L);
    }

    @Test
    void tie_resolvedByMatchScoreDescendingWhenSuperlikesEqual() {
        WinnerCandidate higherScore = candidate(403L, 10, 1, 0, 9.5, 100);
        WinnerCandidate lowerScore = candidate(404L, 10, 1, 0, 3.2, 100);

        WinnerResult result = calculator.calculate(List.of(lowerScore, higherScore), 5, 0.6);

        assertThat(result.winnerTmdbId()).isEqualTo(403L);
    }

    @Test
    void tie_resolvedByRuntimeAscendingWhenMatchScoreEqual() {
        WinnerCandidate shorter = candidate(405L, 10, 1, 0, 5.0, 90);
        WinnerCandidate longer = candidate(406L, 10, 1, 0, 5.0, 150);

        WinnerResult result = calculator.calculate(List.of(longer, shorter), 5, 0.6);

        assertThat(result.winnerTmdbId()).isEqualTo(405L);
    }

    @Test
    void tie_nullRuntimeLosesToKnownRuntime() {
        WinnerCandidate known = candidate(407L, 10, 1, 0, 5.0, 200);
        WinnerCandidate unknown = candidate(408L, 10, 1, 0, 5.0, null);

        WinnerResult result = calculator.calculate(List.of(unknown, known), 5, 0.6);

        assertThat(result.winnerTmdbId()).isEqualTo(407L);
    }

    @Test
    void tie_fullyTiedExceptId_resolvedByTmdbIdAscending() {
        WinnerCandidate higherId = candidate(500L, 10, 1, 0, 5.0, 100);
        WinnerCandidate lowerId = candidate(409L, 10, 1, 0, 5.0, 100);

        WinnerResult result = calculator.calculate(List.of(higherId, lowerId), 5, 0.6);

        assertThat(result.winnerTmdbId()).isEqualTo(409L);
    }

    @Test
    void determinism_repeatedCallSameInput_sameResult() {
        WinnerCandidate a = candidate(601L, 10, 1, 1, 5.0, 100);
        WinnerCandidate b = candidate(602L, 10, 1, 1, 4.0, 90);
        WinnerCandidate c = candidate(603L, 3, 0, 0, 5.0, 100);
        List<WinnerCandidate> candidates = List.of(a, b, c);

        WinnerResult first = calculator.calculate(candidates, 5, 0.6);
        WinnerResult second = calculator.calculate(candidates, 5, 0.6);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void doesNotMutateInputList() {
        WinnerCandidate a = candidate(701L, 3, 0, 0, 5.0, 100);
        WinnerCandidate b = candidate(702L, 10, 0, 0, 5.0, 100);
        List<WinnerCandidate> candidates = new java.util.ArrayList<>(List.of(a, b));

        calculator.calculate(candidates, 5, 0.6);

        assertThat(candidates).containsExactly(a, b);
    }

    @Test
    void top3_containsThreeHighestRankedInOrder() {
        WinnerCandidate first = candidate(801L, 10, 0, 0, 5.0, 100);
        WinnerCandidate second = candidate(802L, 8, 0, 0, 5.0, 100);
        WinnerCandidate third = candidate(803L, 6, 0, 0, 5.0, 100);
        WinnerCandidate fourth = candidate(804L, 4, 0, 0, 5.0, 100);

        WinnerResult result = calculator.calculate(List.of(fourth, first, third, second), 5, 0.6);

        assertThat(result.top3()).extracting(VoteTally::tmdbId).containsExactly(801L, 802L, 803L);
    }

    @Test
    void top3_fewerThanThreeCandidates_returnsAllAvailable() {
        WinnerCandidate a = candidate(901L, 10, 0, 0, 5.0, 100);
        WinnerCandidate b = candidate(902L, 5, 0, 0, 5.0, 100);

        WinnerResult result = calculator.calculate(List.of(a, b), 5, 0.6);

        assertThat(result.top3()).hasSize(2);
    }

    @Test
    void top3_compromiseScenario_drawnFromAllCandidatesNotEmptySurvivors() {
        WinnerCandidate a = candidate(903L, 10, 0, 3, 5.0, 100);
        WinnerCandidate b = candidate(904L, 6, 0, 3, 5.0, 100);

        WinnerResult result = calculator.calculate(List.of(a, b), 5, 0.6);

        assertThat(result.top3()).hasSize(2);
        assertThat(result.top3()).extracting(VoteTally::tmdbId).containsExactly(903L, 904L);
    }

    @Test
    void top3_excludesVetoedCandidatesWhenSurvivorsExist() {
        WinnerCandidate vetoed = candidate(905L, 20, 0, 3, 5.0, 100);
        WinnerCandidate safe1 = candidate(906L, 8, 0, 0, 5.0, 100);
        WinnerCandidate safe2 = candidate(907L, 6, 0, 0, 5.0, 100);
        WinnerCandidate safe3 = candidate(908L, 4, 0, 0, 5.0, 100);

        WinnerResult result = calculator.calculate(List.of(vetoed, safe1, safe2, safe3), 5, 0.6);

        assertThat(result.top3()).extracting(VoteTally::tmdbId).containsExactly(906L, 907L, 908L);
    }
}
