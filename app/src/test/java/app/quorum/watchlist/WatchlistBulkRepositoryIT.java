package app.quorum.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import app.quorum.watchlist.domain.UserMovieKey;
import app.quorum.watchlist.infra.SeenItemRepository;
import app.quorum.watchlist.infra.WatchlistBulkRepository;
import app.quorum.watchlist.infra.WatchlistItemRepository;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WatchlistBulkRepositoryIT {

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
    private WatchlistBulkRepository watchlistBulkRepository;

    @Autowired
    private WatchlistItemRepository watchlistItemRepository;

    @Autowired
    private SeenItemRepository seenItemRepository;

    @Autowired
    private JdbcClient jdbcClient;

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

    @Test
    void removeAndMarkSeen_someoneAlreadyMarkedSeen_stillMarksTheRestWithoutError() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        UUID user3 = UUID.randomUUID();
        insertUser(user1, 810_001L);
        insertUser(user2, 810_002L);
        insertUser(user3, 810_003L);
        long tmdbId = 810_101L;
        insertMovie(tmdbId);
        insertWatchlistItem(user1, tmdbId);
        insertWatchlistItem(user2, tmdbId);
        insertWatchlistItem(user3, tmdbId);
        insertSeenItem(user2, tmdbId);

        assertDoesNotThrow(
                () -> watchlistBulkRepository.removeAndMarkSeen(List.of(user1, user2, user3), tmdbId, Instant.now()));

        for (UUID userId : List.of(user1, user2, user3)) {
            UserMovieKey key = new UserMovieKey(userId, tmdbId);
            assertThat(seenItemRepository.existsById(key)).isTrue();
            assertThat(watchlistItemRepository.existsById(key)).isFalse();
        }
    }

    @Test
    void removeAndMarkSeen_calledTwiceInARow_doesNotThrow() {
        UUID user1 = UUID.randomUUID();
        insertUser(user1, 810_004L);
        long tmdbId = 810_102L;
        insertMovie(tmdbId);
        insertWatchlistItem(user1, tmdbId);

        watchlistBulkRepository.removeAndMarkSeen(List.of(user1), tmdbId, Instant.now());

        assertDoesNotThrow(() -> watchlistBulkRepository.removeAndMarkSeen(List.of(user1), tmdbId, Instant.now()));
        assertThat(seenItemRepository.existsById(new UserMovieKey(user1, tmdbId)))
                .isTrue();
    }

    @Test
    void removeAndMarkSeen_emptyUserSet_doesNotThrow_andChangesNothing() {
        assertDoesNotThrow(() -> watchlistBulkRepository.removeAndMarkSeen(List.of(), 999_997L, Instant.now()));
    }

    // Hibernate Statistics doesn't see plain JdbcClient calls (see
    // WatchlistAggRepositoryIT) - counting calls to JdbcClient.sql(...) instead:
    // the repository calls it exactly twice (one DELETE, one INSERT) regardless
    // of how many users are in the group.
    @Test
    void removeAndMarkSeen_fiveUsers_runsExactlyTwoQueries_notPerUser() {
        List<UUID> users =
                List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        for (int i = 0; i < users.size(); i++) {
            insertUser(users.get(i), 810_010L + i);
        }
        long tmdbId = 810_103L;
        insertMovie(tmdbId);
        for (UUID userId : users) {
            insertWatchlistItem(userId, tmdbId);
        }

        SQL_STATEMENT_COUNT.set(0);

        watchlistBulkRepository.removeAndMarkSeen(users, tmdbId, Instant.now());

        assertThat(SQL_STATEMENT_COUNT.get())
                .as("removeAndMarkSeen() must run exactly two SQL statements (one DELETE, one INSERT)")
                .isEqualTo(2);
    }

    private static final AtomicInteger SQL_STATEMENT_COUNT = new AtomicInteger();

    @TestConfiguration
    static class CountingJdbcClientConfig {
        @Bean
        static BeanPostProcessor jdbcClientCallCounter() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof JdbcClient real) {
                        return (JdbcClient) Proxy.newProxyInstance(
                                JdbcClient.class.getClassLoader(),
                                new Class<?>[] {JdbcClient.class},
                                (proxy, method, args) -> {
                                    if ("sql".equals(method.getName())) {
                                        SQL_STATEMENT_COUNT.incrementAndGet();
                                    }
                                    return method.invoke(real, args);
                                });
                    }
                    return bean;
                }
            };
        }
    }
}
