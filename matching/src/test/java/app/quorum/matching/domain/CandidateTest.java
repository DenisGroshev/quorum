package app.quorum.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CandidateTest {

    private static Candidate fullCandidate() {
        return new Candidate(
                603L,
                3,
                1,
                Instant.parse("2026-01-01T00:00:00Z"),
                Set.of(28, 878),
                878,
                136,
                new BigDecimal("8.7"),
                25000,
                "en",
                false);
    }

    @Test
    void accessors_returnExactlyWhatWasConstructed() {
        Candidate candidate = fullCandidate();

        assertThat(candidate.tmdbId()).isEqualTo(603L);
        assertThat(candidate.ownersCount()).isEqualTo(3);
        assertThat(candidate.seenCount()).isEqualTo(1);
        assertThat(candidate.medianAddedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(candidate.genreIds()).containsExactlyInAnyOrder(28, 878);
        assertThat(candidate.primaryGenreId()).isEqualTo(878);
        assertThat(candidate.runtimeMin()).isEqualTo(136);
        assertThat(candidate.voteAverage()).isEqualByComparingTo("8.7");
        assertThat(candidate.voteCount()).isEqualTo(25000);
        assertThat(candidate.originalLanguage()).isEqualTo("en");
        assertThat(candidate.fromFallback()).isFalse();
    }

    @Test
    void mayBeNull_medianAddedAtPrimaryGenreIdAndRuntimeMin_asDocumented() {
        Candidate candidate =
                new Candidate(603L, 3, 1, null, Set.of(28), null, null, new BigDecimal("8.7"), 25000, "en", true);

        assertThat(candidate.medianAddedAt()).isNull();
        assertThat(candidate.primaryGenreId()).isNull();
        assertThat(candidate.runtimeMin()).isNull();
        assertThat(candidate.fromFallback()).isTrue();
    }

    @Test
    void nullGenreIds_normalizesToEmptySet_ratherThanNullPointer() {
        Candidate candidate = new Candidate(603L, 0, 0, null, null, null, null, new BigDecimal("0"), 0, null, false);

        assertThat(candidate.genreIds()).isNotNull().isEmpty();
    }

    @Test
    void isAPlainValueObject_notAJpaEntity_equalityByValue() {
        assertThat(fullCandidate()).isEqualTo(fullCandidate());
        assertThat(Candidate.class.getAnnotation(jakarta.persistence.Entity.class))
                .isNull();
    }
}
