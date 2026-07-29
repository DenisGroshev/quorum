package app.quorum.common;

import java.util.Map;
import java.util.Set;

public enum SessionState {
    LOBBY,
    VOTING,
    DECIDED,
    WATCHED,
    CANCELLED,
    EXPIRED,
    ARCHIVED;

    private static final Map<SessionState, Set<SessionState>> ALLOWED = Map.of(
            LOBBY, Set.of(VOTING, CANCELLED, EXPIRED),
            VOTING, Set.of(DECIDED, CANCELLED, EXPIRED),
            DECIDED, Set.of(WATCHED, ARCHIVED),
            WATCHED, Set.of(),
            CANCELLED, Set.of(),
            EXPIRED, Set.of(),
            ARCHIVED, Set.of());

    public boolean canTransitionTo(SessionState target) {
        return ALLOWED.get(this).contains(target);
    }
}
