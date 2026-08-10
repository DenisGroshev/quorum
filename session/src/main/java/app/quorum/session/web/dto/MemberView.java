package app.quorum.session.web.dto;

import java.util.UUID;

public record MemberView(
        UUID userId, String displayName, String avatarUrl, boolean isHost, boolean hasFinishedVoting) {}
