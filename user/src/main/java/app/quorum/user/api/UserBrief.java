package app.quorum.user.api;

import java.util.UUID;

public record UserBrief(UUID id, String displayName, String avatarUrl) {}
