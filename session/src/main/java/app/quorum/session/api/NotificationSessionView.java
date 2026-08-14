package app.quorum.session.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NotificationSessionView(
        UUID hostId,
        String title,
        String inviteCode,
        List<UUID> memberIds,
        Long winnerTmdbId,
        Instant votingDeadlineAt) {}
