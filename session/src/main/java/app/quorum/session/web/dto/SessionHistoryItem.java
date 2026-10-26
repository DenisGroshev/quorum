package app.quorum.session.web.dto;

import app.quorum.common.SessionState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SessionHistoryItem(
        UUID id,
        String title,
        SessionState state,
        Instant createdAt,
        Long winnerTmdbId,
        String winnerTitle,
        String winnerPosterUrl,
        BigDecimal averageRating) {}
