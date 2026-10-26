package app.quorum.matching.domain;

import app.quorum.matching.config.MatchingProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class MovieScorer {

    private static final BigDecimal RATING_FLOOR = BigDecimal.valueOf(5);
    private static final BigDecimal RATING_SPAN = BigDecimal.valueOf(3);
    private static final BigDecimal HIGH_RATING_THRESHOLD = BigDecimal.valueOf(7);

    private final MatchingProperties properties;

    public MovieScorer(MatchingProperties properties) {
        this.properties = properties;
    }

    public BigDecimal score(Candidate candidate, ScoringContext context) {
        MatchingProperties.Weights w = properties.weights();
        MatchingProperties.Penalties p = properties.penalties();

        double total = w.overlap() * overlapScore(candidate, context)
                + w.genreFit() * genreFitScore(candidate, context)
                + w.rating() * ratingScore(candidate)
                + w.recency() * recencyScore(candidate, context.now())
                + w.availability() * availabilityScore()
                - p.seenShare() * seenShareFraction(candidate, context)
                - p.longRuntime() * longRuntimePenaltyIndicator(candidate)
                - p.foreignLowRated() * foreignLowRatedPenaltyIndicator(candidate, context.memberLanguageCodes());

        return BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP);
    }

    double overlapScore(Candidate candidate, ScoringContext context) {
        if (context.participantCount() == 0) {
            return 0.0;
        }
        return (double) candidate.ownersCount() / context.participantCount();
    }

    double genreFitScore(Candidate candidate, ScoringContext context) {
        if (context.memberFavGenres().isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (Set<Integer> favGenres : context.memberFavGenres()) {
            sum += jaccard(candidate.genreIds(), favGenres);
        }
        return sum / context.memberFavGenres().size();
    }

    double ratingScore(Candidate candidate) {
        if (candidate.voteCount() < properties.minVoteCountForRating()) {
            return properties.defaultRatingWhenFewVotes();
        }
        double normalized = candidate
                .voteAverage()
                .subtract(RATING_FLOOR)
                .divide(RATING_SPAN, 10, RoundingMode.HALF_UP)
                .doubleValue();
        return clamp01(normalized);
    }

    double recencyScore(Candidate candidate, Instant now) {
        if (candidate.medianAddedAt() == null) {
            return 0.0;
        }
        long daysSince = Duration.between(candidate.medianAddedAt(), now).toDays();
        double fraction = 1.0 - (double) daysSince / properties.recencyHorizonDays();
        return Math.max(0.0, fraction);
    }

    // Заглушка до этапа 2 (интеграция со стримингами). Решение зафиксировано в ТЗ п. 4.6, FR-MATCH-3.
    double availabilityScore() {
        return properties.availabilityStub();
    }

    double seenShareFraction(Candidate candidate, ScoringContext context) {
        if (context.participantCount() == 0) {
            return 0.0;
        }
        return (double) candidate.seenCount() / context.participantCount();
    }

    double longRuntimePenaltyIndicator(Candidate candidate) {
        Integer runtimeMin = candidate.runtimeMin();
        return runtimeMin != null && runtimeMin > properties.runtimePenaltyThresholdMin() ? 1.0 : 0.0;
    }

    double foreignLowRatedPenaltyIndicator(Candidate candidate, Set<String> memberLanguageCodes) {
        String language = candidate.originalLanguage();
        if (language == null) {
            return 0.0;
        }
        boolean isForeign = !memberLanguageCodes.contains(language.toLowerCase());
        boolean isLowRated = candidate.voteAverage().compareTo(HIGH_RATING_THRESHOLD) < 0;
        return isForeign && isLowRated ? 1.0 : 0.0;
    }

    private static double jaccard(Set<Integer> a, Set<Integer> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0.0;
        }
        Set<Integer> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        if (intersection.isEmpty()) {
            return 0.0;
        }
        Set<Integer> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
