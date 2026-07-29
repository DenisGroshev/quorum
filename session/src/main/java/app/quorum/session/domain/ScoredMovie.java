package app.quorum.session.domain;

import java.math.BigDecimal;
import java.util.List;

public record ScoredMovie(Long tmdbId, BigDecimal score, List<DeckReason> reasonsJson) {}
