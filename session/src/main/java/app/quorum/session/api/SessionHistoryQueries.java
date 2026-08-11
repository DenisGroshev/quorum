package app.quorum.session.api;

import java.util.Set;
import java.util.UUID;

public interface SessionHistoryQueries {
    Set<Long> pastWinnersForExactMemberSet(Set<UUID> memberIds);
}
