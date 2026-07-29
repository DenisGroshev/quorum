package app.quorum.catalog.api;

import java.math.BigDecimal;

public record MovieDto(
        long tmdbId,
        String title,
        String overview,
        String posterPath,
        Short releaseYear,
        Short runtimeMin,
        BigDecimal voteAvg) {}
