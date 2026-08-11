package app.quorum.voting.web.dto;

import app.quorum.voting.domain.VoteValue;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

public record DeckCardResponse(
        long tmdbId,
        String title,
        String posterUrl,
        Integer releaseYear,
        Set<Integer> genreIds,
        Integer runtimeMin,
        BigDecimal voteAverage,
        String reasonCode,
        Map<String, Object> reasonArgs,
        VoteValue myVote) {}
