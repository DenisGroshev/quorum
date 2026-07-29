package app.quorum.session.api;

import java.util.List;
import java.util.UUID;

public record VotingContext(UUID sessionId, List<Long> deckTmdbIds, boolean votingOpen) {}
