package app.quorum.watchlist.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WatchlistPaginationQueryPlanIT {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("TG_BOT_TOKEN", () -> "unused-in-this-test");
        registry.add("JWT_SECRET", () -> "integration-test-jwt-secret-should-be-long-enough");
        registry.add("TG_APP_NAME", () -> "quorum-it-tests");
        registry.add("TMDB_TOKEN", () -> "unused-in-this-test");
    }

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void watchlistAndSeenPages_queryPlan_useIndexScan_notSeqScan() {
        jdbcClient
                .sql(
                        """
                        INSERT INTO users (id, tg_user_id, display_name, created_at)
                        SELECT gen_random_uuid(), 910000 + gs, 'Bulk User ' || gs, now()
                        FROM generate_series(1, 300) AS gs
                        """)
                .update();
        jdbcClient
                .sql(
                        """
                        INSERT INTO movies (tmdb_id, original_title, vote_avg, vote_count, synced_at)
                        SELECT 910000 + gs, 'Bulk Movie ' || gs, 7.5, 100, now()
                        FROM generate_series(0, 49) AS gs
                        """)
                .update();
        jdbcClient
                .sql(
                        """
                        INSERT INTO watchlist_items (user_id, tmdb_id, added_at)
                        SELECT u.id, m.tmdb_id, now() - (random() * interval '30 days')
                        FROM (SELECT id FROM users WHERE tg_user_id BETWEEN 910001 AND 910300) u
                        CROSS JOIN (SELECT tmdb_id FROM movies WHERE tmdb_id BETWEEN 910000 AND 910049) m
                        WHERE random() < 0.5
                        """)
                .update();
        jdbcClient
                .sql(
                        """
                        INSERT INTO seen_items (user_id, tmdb_id, marked_at)
                        SELECT u.id, m.tmdb_id, now() - (random() * interval '30 days')
                        FROM (SELECT id FROM users WHERE tg_user_id BETWEEN 910001 AND 910300) u
                        CROSS JOIN (SELECT tmdb_id FROM movies WHERE tmdb_id BETWEEN 910000 AND 910049) m
                        WHERE random() < 0.5
                        """)
                .update();
        jdbcClient.sql("ANALYZE watchlist_items").update();
        jdbcClient.sql("ANALYZE seen_items").update();

        UUID targetUser = UUID.randomUUID();
        insertUser(targetUser, 800_030L);
        long tmdbId = 800_107L;
        insertMovie(tmdbId);
        insertWatchlistItem(targetUser, tmdbId);
        insertSeenItem(targetUser, tmdbId);

        String watchlistPlan = explain(
                "SELECT * FROM watchlist_items WHERE user_id = :userId ORDER BY added_at DESC LIMIT 20", targetUser);
        assertThat(watchlistPlan)
                .as("watchlist_items query plan:%n%s", watchlistPlan)
                .contains("Index Scan")
                .contains("idx_wl_user_added")
                .doesNotContain("Seq Scan on watchlist_items");

        String seenPlan = explain(
                "SELECT * FROM seen_items WHERE user_id = :userId ORDER BY marked_at DESC LIMIT 20", targetUser);
        assertThat(seenPlan)
                .as("seen_items query plan:%n%s", seenPlan)
                .contains("Index Scan")
                .contains("idx_seen_user_marked")
                .doesNotContain("Seq Scan on seen_items");
    }

    private String explain(String sql, UUID userId) {
        List<String> planLines = jdbcClient
                .sql("EXPLAIN (ANALYZE, FORMAT TEXT) " + sql)
                .param("userId", userId)
                .query(String.class)
                .list();
        return String.join("\n", planLines);
    }

    private void insertUser(UUID id, long tgUserId) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO users (id, tg_user_id, display_name, created_at)
                        VALUES (:id, :tgUserId, :displayName, :createdAt)
                        """)
                .param("id", id)
                .param("tgUserId", tgUserId)
                .param("displayName", "User " + tgUserId)
                .param("createdAt", Timestamp.from(Instant.now()))
                .update();
    }

    private void insertMovie(long tmdbId) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO movies (tmdb_id, original_title, vote_avg, vote_count, synced_at)
                        VALUES (:tmdbId, :title, :voteAvg, :voteCount, :syncedAt)
                        """)
                .param("tmdbId", tmdbId)
                .param("title", "Movie " + tmdbId)
                .param("voteAvg", new BigDecimal("7.5"))
                .param("voteCount", 100)
                .param("syncedAt", Timestamp.from(Instant.now()))
                .update();
    }

    private void insertWatchlistItem(UUID userId, long tmdbId) {
        jdbcClient
                .sql("INSERT INTO watchlist_items (user_id, tmdb_id, added_at) VALUES (:userId, :tmdbId, :addedAt)")
                .param("userId", userId)
                .param("tmdbId", tmdbId)
                .param("addedAt", Timestamp.from(Instant.now()))
                .update();
    }

    private void insertSeenItem(UUID userId, long tmdbId) {
        jdbcClient
                .sql("INSERT INTO seen_items (user_id, tmdb_id, marked_at) VALUES (:userId, :tmdbId, :markedAt)")
                .param("userId", userId)
                .param("tmdbId", tmdbId)
                .param("markedAt", Timestamp.from(Instant.now()))
                .update();
    }
}
