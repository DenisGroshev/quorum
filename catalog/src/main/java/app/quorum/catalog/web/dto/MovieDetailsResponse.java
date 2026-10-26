package app.quorum.catalog.web.dto;

import java.math.BigDecimal;
import java.util.List;

public record MovieDetailsResponse(
        long tmdbId,
        String title,
        String originalTitle,
        Integer year,
        Integer runtimeMin,
        String posterUrl,
        List<String> genres,
        BigDecimal voteAverage,
        String overview) {}
