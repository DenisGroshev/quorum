package app.quorum.matching.api;

import java.util.List;
import java.util.UUID;

public record DeckRequest(UUID sessionId, List<UUID> memberIds, int deckSize, boolean watchlistOnly) {}
