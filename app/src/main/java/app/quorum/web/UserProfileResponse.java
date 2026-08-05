package app.quorum.web;

import java.util.UUID;

public record UserProfileResponse(
        UUID id, String displayName, String username, boolean notifyEnabled, String langContent) {}
