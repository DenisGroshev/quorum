package app.quorum.matching.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

public record Candidate(
        long tmdbId,
        int ownersCount,
        int seenCount,
        Instant medianAddedAt,
        Set<Integer> genreIds,
        Integer primaryGenreId,
        Integer runtimeMin,
        BigDecimal voteAverage,
        int voteCount,
        String originalLanguage,
        boolean fromFallback) {

    public Candidate {
        genreIds = genreIds != null ? Set.copyOf(genreIds) : Set.of();
    }
}
