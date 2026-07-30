package app.quorum.auth.api;

import java.util.UUID;

public record AccessTokenClaims(UUID userId, int tokenVer) {}
