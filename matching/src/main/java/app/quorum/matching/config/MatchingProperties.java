package app.quorum.matching.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "quorum.matching")
public record MatchingProperties(
        @NotBlank String version,
        @NotNull @Valid Weights weights,
        @NotNull @Valid Penalties penalties,
        @Positive int runtimePenaltyThresholdMin,
        @Positive int recencyHorizonDays,
        @Positive int minVoteCountForRating,
        @DecimalMin("0.0") @DecimalMax("1.0") double defaultRatingWhenFewVotes,
        @DecimalMin("0.0") @DecimalMax("1.0") double maxGenreShare,
        @Positive int candidateMultiplier,
        // Заглушка до этапа 2 (интеграция со стримингами). Решение зафиксировано в ТЗ п. 4.6, FR-MATCH-3.
        @DecimalMin("0.0") @DecimalMax("1.0") double availabilityStub) {

    public record Weights(
            @PositiveOrZero int overlap,
            @PositiveOrZero int genreFit,
            @PositiveOrZero int rating,
            @PositiveOrZero int recency,
            @PositiveOrZero int availability) {}

    public record Penalties(
            @PositiveOrZero int seenShare, @PositiveOrZero int longRuntime, @PositiveOrZero int foreignLowRated) {}
}
