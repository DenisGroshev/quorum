package app.quorum.voting.infra;

import app.quorum.voting.domain.VoteTally;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class VoteTallyRepository {

    private static final String SQL_AGGREGATE =
            """
            SELECT v.tmdb_id,
                   SUM(CASE v.value WHEN 'LIKE' THEN 1 WHEN 'SUPERLIKE' THEN 2 ELSE 0 END) AS points,
                   COUNT(*) FILTER (WHERE v.value = 'SUPERLIKE') AS superlikes,
                   COUNT(*) FILTER (WHERE v.value = 'DISLIKE') AS dislikes
            FROM votes v
            WHERE v.session_id = :sessionId
            GROUP BY v.tmdb_id
            """;

    private final JdbcClient jdbcClient;

    public VoteTallyRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<VoteTally> tallyFor(UUID sessionId) {
        return jdbcClient
                .sql(SQL_AGGREGATE)
                .param("sessionId", sessionId)
                .query((rs, rowNum) -> new VoteTally(
                        rs.getLong("tmdb_id"), rs.getInt("points"), rs.getInt("superlikes"), rs.getInt("dislikes")))
                .list();
    }
}
