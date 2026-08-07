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
class WatchlistAggRepositoryQueryPlanIT {

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
    void aggregateFor_queryPlan_usesIndexScan_notSeqScan_onWatchlistItems() {
        jdbcClient
                .sql(
                        """
                        INSERT INTO users (id, tg_user_id, display_name, created_at)
                        SELECT gen_random_uuid(), 900000 + gs, 'Bulk User ' || gs, now()
                        FROM generate_series(1, 300) AS gs
                        """)
                .update();
        jdbcClient
                .sql(
                        """
                        INSERT INTO movies (tmdb_id, original_title, vote_avg, vote_count, synced_at)
                        SELECT 900000 + gs, 'Bulk Movie ' || gs, 7.5, 100, now()
                        FROM generate_series(0, 49) AS gs
                        """)
                .update();
        jdbcClient
                .sql(
                        """
                        INSERT INTO watchlist_items (user_id, tmdb_id, added_at)
                        SELECT u.id, m.tmdb_id, now() - (random() * interval '30 days')
                        FROM (SELECT id FROM users WHERE tg_user_id BETWEEN 900001 AND 900300) u
                        CROSS JOIN (SELECT tmdb_id FROM movies WHERE tmdb_id BETWEEN 900000 AND 900049) m
                        WHERE random() < 0.5
                        """)
                .update();
        // The planner relies on table statistics, not just row count - without
        // ANALYZE it may still guess a Seq Scan even on a large table.
        jdbcClient.sql("ANALYZE watchlist_items").update();
        jdbcClient.sql("ANALYZE seen_items").update();

        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        UUID user3 = UUID.randomUUID();
        insertUser(user1, 800_020L);
        insertUser(user2, 800_021L);
        insertUser(user3, 800_022L);
        long tmdbId = 800_106L;
        insertMovie(tmdbId);
        insertWatchlistItem(user1, tmdbId);
        insertWatchlistItem(user2, tmdbId);

        List<String> planLines = jdbcClient
                .sql("EXPLAIN (ANALYZE, FORMAT TEXT) " + WatchlistAggRepository.AGGREGATE_SQL)
                .param("memberIds", List.of(user1, user2, user3))
                .param("cnt", 3)
                .query(String.class)
                .list();

        String plan = String.join("\n", planLines);
        assertThat(plan)
                .as("query plan:%n%s", plan)
                .contains("Index Scan")
                .doesNotContain("Seq Scan on watchlist_items");
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
}
