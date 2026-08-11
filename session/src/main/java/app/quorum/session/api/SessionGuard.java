package app.quorum.session.api;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface SessionGuard {
    VotingContext requireVotingMember(UUID sessionId, UUID userId);

    boolean markMemberFinishedVoting(UUID sessionId, UUID userId, Clock clock);

    Map<Long, BigDecimal> deckScores(UUID sessionId);

    boolean concludeVoting(UUID sessionId, Long winnerTmdbId, boolean contested, Clock clock);

    List<UUID> votingSessionsPastDeadline(Instant cutoff, int limit);

    List<DeckCardInfo> deckCards(UUID sessionId);

    VotingProgress votingProgress(UUID sessionId);

    void requireHost(UUID sessionId, UUID userId);
}
