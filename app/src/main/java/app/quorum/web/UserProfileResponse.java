package app.quorum.web;

import java.util.Set;
import java.util.UUID;

public record UserProfileResponse(
        UUID id,
        String displayName,
        String username,
        boolean notifyEnabled,
        String langContent,
        long watchlistCount,
        long movieNightsCount,
        Set<Integer> favGenreIds) {}
