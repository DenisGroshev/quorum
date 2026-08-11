package app.quorum.session.infra;

import app.quorum.session.api.SessionHistoryQueries;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SessionHistoryQueriesImpl implements SessionHistoryQueries {

    private static final String SQL =
            """
            SELECT s.winner_tmdb_id
            FROM sessions s
            JOIN session_members m ON m.session_id = s.id
            WHERE s.state IN ('DECIDED', 'WATCHED', 'ARCHIVED') AND s.winner_tmdb_id IS NOT NULL
            GROUP BY s.id, s.winner_tmdb_id
            HAVING COUNT(*) FILTER (WHERE m.user_id IN (:memberIds)) = :memberCount
               AND COUNT(*) = :memberCount
            """;

    private final JdbcClient jdbcClient;

    public SessionHistoryQueriesImpl(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Set<Long> pastWinnersForExactMemberSet(Set<UUID> memberIds) {
        if (memberIds.isEmpty()) {
            return Set.of();
        }

        return Set.copyOf(jdbcClient
                .sql(SQL)
                .param("memberIds", memberIds)
                .param("memberCount", memberIds.size())
                .query(Long.class)
                .list());
    }
}
