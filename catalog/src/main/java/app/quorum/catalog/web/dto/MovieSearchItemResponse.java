package app.quorum.catalog.web.dto;

import java.math.BigDecimal;
import java.util.List;

public record MovieSearchItemResponse(
        long tmdbId,
        String title,
        Integer year,
        String posterUrl,
        List<String> genres,
        BigDecimal voteAverage,
        // TODO(срез 3, watchlist): всегда false, пока watchlist не готов.
        boolean inWatchlist,
        // TODO(срез 3, watchlist): всегда false, пока watchlist не готов.
        boolean seen) {}
