package app.quorum.matching.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

final class CandidateFixture {

    private long tmdbId = 1L;
    private int ownersCount = 0;
    private int seenCount = 0;
    private Instant medianAddedAt = null;
    private Set<Integer> genreIds = Set.of();
    private Integer primaryGenreId = null;
    private Integer runtimeMin = null;
    private BigDecimal voteAverage = BigDecimal.ZERO;
    private int voteCount = 0;
    private String originalLanguage = null;
    private boolean fromFallback = false;

    static CandidateFixture candidate() {
        return new CandidateFixture();
    }

    CandidateFixture tmdbId(long value) {
        this.tmdbId = value;
        return this;
    }

    CandidateFixture ownersCount(int value) {
        this.ownersCount = value;
        return this;
    }

    CandidateFixture seenCount(int value) {
        this.seenCount = value;
        return this;
    }

    CandidateFixture medianAddedAt(Instant value) {
        this.medianAddedAt = value;
        return this;
    }

    CandidateFixture genreIds(Set<Integer> value) {
        this.genreIds = value;
        return this;
    }

    CandidateFixture primaryGenreId(Integer value) {
        this.primaryGenreId = value;
        return this;
    }

    CandidateFixture runtimeMin(Integer value) {
        this.runtimeMin = value;
        return this;
    }

    CandidateFixture voteAverage(BigDecimal value) {
        this.voteAverage = value;
        return this;
    }

    CandidateFixture voteCount(int value) {
        this.voteCount = value;
        return this;
    }

    CandidateFixture originalLanguage(String value) {
        this.originalLanguage = value;
        return this;
    }

    CandidateFixture fromFallback(boolean value) {
        this.fromFallback = value;
        return this;
    }

    Candidate build() {
        return new Candidate(
                tmdbId,
                ownersCount,
                seenCount,
                medianAddedAt,
                genreIds,
                primaryGenreId,
                runtimeMin,
                voteAverage,
                voteCount,
                originalLanguage,
                fromFallback);
    }
}
