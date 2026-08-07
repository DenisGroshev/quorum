package app.quorum.watchlist.infra;

import app.quorum.watchlist.api.WatchlistAgg;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WatchlistAggRepository {

    static final String AGGREGATE_SQL =
            """
            SELECT w.tmdb_id,
                   COUNT(*) AS owners,
                   to_timestamp(percentile_cont(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM w.added_at)))
                       AS median_added,
                   COALESCE(s.seen_cnt, 0) AS seen_cnt
            FROM watchlist_items w
            LEFT JOIN (SELECT tmdb_id, COUNT(*) AS seen_cnt
                       FROM seen_items WHERE user_id IN (:memberIds) GROUP BY tmdb_id) s
                   USING (tmdb_id)
            WHERE w.user_id IN (:memberIds)
            GROUP BY w.tmdb_id, s.seen_cnt
            HAVING COALESCE(s.seen_cnt, 0) < :cnt
            """;

    private final JdbcClient jdbcClient;

    public WatchlistAggRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<WatchlistAgg> aggregateFor(Collection<UUID> memberIds) {
        if (memberIds.isEmpty()) {
            return List.of();
        }

        return jdbcClient
                .sql(AGGREGATE_SQL)
                .param("memberIds", memberIds)
                .param("cnt", memberIds.size())
                .query((rs, rowNum) -> new WatchlistAgg(
                        rs.getLong("tmdb_id"),
                        rs.getInt("owners"),
                        rs.getTimestamp("median_added").toInstant(),
                        rs.getInt("seen_cnt")))
                .list();
    }
}
