package app.quorum.watchlist.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface WatchlistQueries {
    Map<Long, WatchlistAgg> aggregateFor(Collection<UUID> userIds);

    void removeAndMarkSeen(Collection<UUID> userIds, long tmdbId);
}
