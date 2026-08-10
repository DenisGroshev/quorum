package app.quorum.session.web.dto;

import app.quorum.common.SessionState;
import java.time.Instant;
import java.util.UUID;

public record SessionSummary(UUID id, String title, SessionState state, Instant createdAt) {}
