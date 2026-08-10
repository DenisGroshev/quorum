package app.quorum.session.web.dto;

import java.util.List;

public record SessionListResponse(List<SessionSummary> active, List<SessionHistoryItem> history) {}
