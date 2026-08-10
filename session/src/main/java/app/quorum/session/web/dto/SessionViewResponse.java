package app.quorum.session.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SessionViewResponse(
        UUID id,
        String title,
        String state,
        boolean isHost,
        List<MemberView> members,
        int deckSize,
        Instant votingDeadlineAt,
        String inviteUrl,
        Long winnerTmdbId) {}
