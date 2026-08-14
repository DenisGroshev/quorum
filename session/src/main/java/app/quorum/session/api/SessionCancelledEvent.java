package app.quorum.session.api;

import java.util.UUID;

public record SessionCancelledEvent(UUID sessionId) {}
