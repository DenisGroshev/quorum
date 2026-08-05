package app.quorum.catalog.api;

import java.math.BigDecimal;
import java.util.Set;

public record MovieDto(
        long tmdbId,
        String title,
        String originalTitle,
        Integer releaseYear,
        Integer runtimeMin,
        String posterUrl,
        Set<Integer> genreIds,
        Integer primaryGenreId,
        BigDecimal voteAverage,
        int voteCount,
        String originalLanguage,
        String overview) {}
