package app.quorum.session.api;

import java.util.UUID;

public interface SessionGuard {
    VotingContext requireVotingMember(UUID sessionId, UUID userId);
}
