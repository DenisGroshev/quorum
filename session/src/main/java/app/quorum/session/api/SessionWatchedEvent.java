package app.quorum.session.api;

import java.util.List;
import java.util.UUID;

public record SessionWatchedEvent(List<UUID> memberIds, long winnerTmdbId) {}
