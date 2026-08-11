package app.quorum.session.api;

import java.util.Map;

public record DeckCardInfo(long tmdbId, String reasonCode, Map<String, Object> reasonArgs) {}
