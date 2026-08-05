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
        String overview,
        // TODO(шаг 3, watchlist): всегда false, пока watchlist не готов.
        boolean inWatchlist,
        // TODO(шаг 3, watchlist): всегда false, пока watchlist не готов.
        boolean seen) {}
