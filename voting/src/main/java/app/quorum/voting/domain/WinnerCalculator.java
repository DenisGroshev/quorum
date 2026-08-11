package app.quorum.voting.domain;

import java.util.Comparator;
import java.util.List;

public class WinnerCalculator {

    private static final Comparator<WinnerCandidate> RANKING = Comparator.comparingInt(
                    (WinnerCandidate c) -> c.tally().points())
            .reversed()
            .thenComparing(
                    Comparator.comparingInt((WinnerCandidate c) -> c.tally().superlikes())
                            .reversed())
            .thenComparing(
                    Comparator.comparing((WinnerCandidate c) -> c.matchScore()).reversed())
            .thenComparing(WinnerCandidate::runtimeMin, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingLong(c -> c.tally().tmdbId());

    public WinnerResult calculate(List<WinnerCandidate> candidates, int votersCount, double vetoThreshold) {
        if (candidates.isEmpty()) {
            return WinnerResult.empty();
        }

        List<WinnerCandidate> survivors = candidates.stream()
                .filter(c -> !isVetoed(c, votersCount, vetoThreshold))
                .toList();

        boolean contested = survivors.isEmpty();
        List<WinnerCandidate> pool = contested ? candidates : survivors;
        List<WinnerCandidate> ranked = pool.stream().sorted(RANKING).toList();

        List<VoteTally> top3 =
                ranked.stream().limit(3).map(WinnerCandidate::tally).toList();

        return new WinnerResult(ranked.get(0).tally().tmdbId(), contested, top3);
    }

    private boolean isVetoed(WinnerCandidate candidate, int votersCount, double vetoThreshold) {
        double dislikeRatio = (double) candidate.tally().dislikes() / votersCount;
        return dislikeRatio >= vetoThreshold;
    }
}
