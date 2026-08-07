package app.quorum.watchlist;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.watchlist.api.WatchlistAgg;
import app.quorum.watchlist.infra.WatchlistAggRepository;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
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
class WatchlistAggRepositoryIT {

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
    private WatchlistAggRepository watchlistAggRepository;

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

    private void insertWatchlistItem(UUID userId, long tmdbId, Instant addedAt) {
        jdbcClient
                .sql("INSERT INTO watchlist_items (user_id, tmdb_id, added_at) VALUES (:userId, :tmdbId, :addedAt)")
                .param("userId", userId)
                .param("tmdbId", tmdbId)
                .param("addedAt", Timestamp.from(addedAt))
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
    void aggregateFor_twoOfThreeShareAMovie_ownersCountIsTwo() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        UUID user3 = UUID.randomUUID();
        insertUser(user1, 800_001L);
        insertUser(user2, 800_002L);
        insertUser(user3, 800_003L);
        long tmdbId = 800_101L;
        insertMovie(tmdbId);
        insertWatchlistItem(user1, tmdbId, Instant.now());
        insertWatchlistItem(user2, tmdbId, Instant.now());
        // user3 does not have it - not a member of the pair sharing this movie

        List<WatchlistAgg> result = watchlistAggRepository.aggregateFor(List.of(user1, user2, user3));

        Map<Long, WatchlistAgg> byTmdbId = result.stream().collect(Collectors.toMap(WatchlistAgg::tmdbId, a -> a));
        assertThat(byTmdbId.get(tmdbId).ownersCount()).isEqualTo(2);
        assertThat(byTmdbId.get(tmdbId).seenCount()).isEqualTo(0);
    }

    @Test
    void aggregateFor_movieSeenByEveryoneInTheGroup_isExcludedFromResult() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        UUID user3 = UUID.randomUUID();
        insertUser(user1, 800_004L);
        insertUser(user2, 800_005L);
        insertUser(user3, 800_006L);
        long tmdbId = 800_102L;
        insertMovie(tmdbId);
        insertWatchlistItem(user1, tmdbId, Instant.now());
        insertSeenItem(user1, tmdbId);
        insertSeenItem(user2, tmdbId);
        insertSeenItem(user3, tmdbId);

        List<WatchlistAgg> result = watchlistAggRepository.aggregateFor(List.of(user1, user2, user3));

        assertThat(result).extracting(WatchlistAgg::tmdbId).doesNotContain(tmdbId);
    }

    @Test
    void aggregateFor_movieSeenByOneOfThree_isIncluded_withSeenCountOne() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        UUID user3 = UUID.randomUUID();
        insertUser(user1, 800_007L);
        insertUser(user2, 800_008L);
        insertUser(user3, 800_009L);
        long tmdbId = 800_103L;
        insertMovie(tmdbId);
        insertWatchlistItem(user1, tmdbId, Instant.now());
        insertWatchlistItem(user2, tmdbId, Instant.now());
        insertWatchlistItem(user3, tmdbId, Instant.now());
        insertSeenItem(user1, tmdbId);

        List<WatchlistAgg> result = watchlistAggRepository.aggregateFor(List.of(user1, user2, user3));

        Map<Long, WatchlistAgg> byTmdbId = result.stream().collect(Collectors.toMap(WatchlistAgg::tmdbId, a -> a));
        assertThat(byTmdbId.get(tmdbId).ownersCount()).isEqualTo(3);
        assertThat(byTmdbId.get(tmdbId).seenCount()).isEqualTo(1);
    }

    @Test
    void aggregateFor_emptyMemberSet_returnsEmptyResult_withoutHittingTheDatabase() {
        List<WatchlistAgg> result = watchlistAggRepository.aggregateFor(List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void aggregateFor_medianAddedAt_isTheMiddleDate() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        UUID user3 = UUID.randomUUID();
        insertUser(user1, 800_010L);
        insertUser(user2, 800_011L);
        insertUser(user3, 800_012L);
        long tmdbId = 800_104L;
        insertMovie(tmdbId);
        Instant day1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant day2 = Instant.parse("2026-01-02T00:00:00Z");
        Instant day3 = Instant.parse("2026-01-03T00:00:00Z");
        insertWatchlistItem(user1, tmdbId, day1);
        insertWatchlistItem(user2, tmdbId, day2);
        insertWatchlistItem(user3, tmdbId, day3);

        List<WatchlistAgg> result = watchlistAggRepository.aggregateFor(List.of(user1, user2, user3));

        WatchlistAgg agg =
                result.stream().filter(a -> a.tmdbId() == tmdbId).findFirst().orElseThrow();
        assertThat(agg.medianAddedAt().truncatedTo(ChronoUnit.SECONDS)).isEqualTo(day2);
    }

    @Test
    void aggregateFor_runsExactlyOneQuery_regardlessOfMemberCount() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        UUID user3 = UUID.randomUUID();
        insertUser(user1, 800_013L);
        insertUser(user2, 800_014L);
        insertUser(user3, 800_015L);
        long tmdbId = 800_105L;
        insertMovie(tmdbId);
        insertWatchlistItem(user1, tmdbId, Instant.now());
        insertWatchlistItem(user2, tmdbId, Instant.now());

        SQL_STATEMENT_COUNT.set(0);

        watchlistAggRepository.aggregateFor(List.of(user1, user2, user3));

        assertThat(SQL_STATEMENT_COUNT.get())
                .as("aggregateFor() must run exactly one SQL query")
                .isEqualTo(1);
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
