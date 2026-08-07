package app.quorum.watchlist.infra;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WatchlistBulkRepository {

    private static final String DELETE_FROM_WATCHLIST_SQL =
            "DELETE FROM watchlist_items WHERE user_id IN (:userIds) AND tmdb_id = :tmdbId";

    private static final String INSERT_INTO_SEEN_SQL =
            """
            INSERT INTO seen_items (user_id, tmdb_id, marked_at)
            SELECT u.id, :tmdbId, :markedAt
            FROM users u
            WHERE u.id IN (:userIds)
            ON CONFLICT (user_id, tmdb_id) DO NOTHING
            """;

    private final JdbcClient jdbcClient;

    public WatchlistBulkRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void removeAndMarkSeen(Collection<UUID> userIds, long tmdbId, Instant markedAt) {
        if (userIds.isEmpty()) {
            return;
        }

        jdbcClient
                .sql(DELETE_FROM_WATCHLIST_SQL)
                .param("userIds", userIds)
                .param("tmdbId", tmdbId)
                .update();

        jdbcClient
                .sql(INSERT_INTO_SEEN_SQL)
                .param("userIds", userIds)
                .param("tmdbId", tmdbId)
                .param("markedAt", Timestamp.from(markedAt))
                .update();
    }
}
