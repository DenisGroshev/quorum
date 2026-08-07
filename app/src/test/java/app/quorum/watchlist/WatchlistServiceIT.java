package app.quorum.watchlist;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import app.quorum.common.ContentLang;
import app.quorum.watchlist.api.MovieFlags;
import app.quorum.watchlist.domain.UserMovieKey;
import app.quorum.watchlist.domain.WatchlistService;
import app.quorum.watchlist.infra.SeenItemRepository;
import app.quorum.watchlist.infra.WatchlistItemRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
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
class WatchlistServiceIT {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final WireMockServer TMDB =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        TMDB.start();
    }

    @AfterAll
    static void stopWireMock() {
        TMDB.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("TG_BOT_TOKEN", () -> "unused-in-this-test");
        registry.add("JWT_SECRET", () -> "integration-test-jwt-secret-should-be-long-enough");
        registry.add("TG_APP_NAME", () -> "quorum-it-tests");
        registry.add("TMDB_TOKEN", () -> "unused-in-this-test");
        registry.add("TMDB_BASE_URL", TMDB::baseUrl);
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
    }

    @Autowired
    private WatchlistService watchlistService;

    @Autowired
    private WatchlistItemRepository watchlistItemRepository;

    @Autowired
    private SeenItemRepository seenItemRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

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

    private void insertFreshMovie(long tmdbId) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO movies (tmdb_id, original_title, vote_avg, vote_count, synced_at)
                        VALUES (:tmdbId, :title, :voteAvg, :voteCount, :syncedAt)
                        """)
                .param("tmdbId", tmdbId)
                .param("title", "Original " + tmdbId)
                .param("voteAvg", new BigDecimal("7.5"))
                .param("voteCount", 100)
                .param("syncedAt", Timestamp.from(Instant.now()))
                .update();
    }

    @Test
    void addToWatchlist_movieNotYetCachedLocally_fetchesFromTmdbAndPersistsWithoutFkViolation() {
        UUID userId = UUID.randomUUID();
        insertUser(userId, 700_010L);
        long tmdbId = 700_010L;
        TMDB.stubFor(get(urlPathEqualTo("/movie/" + tmdbId))
                .willReturn(okJson(
                        """
                        {"id": %d, "title": "Test Movie", "original_title": "Test Movie Original",
                         "overview": "Overview", "vote_average": 7.5, "vote_count": 100}
                        """
                                .formatted(tmdbId))));

        assertDoesNotThrow(() -> watchlistService.addToWatchlist(userId, tmdbId, ContentLang.RU));

        assertThat(watchlistItemRepository.existsById(new UserMovieKey(userId, tmdbId)))
                .isTrue();
    }

    @Test
    void addToWatchlist_twoConcurrentAdds_convergeToExactlyOneRow() throws Exception {
        UUID userId = UUID.randomUUID();
        insertUser(userId, 700_020L);
        long tmdbId = 700_020L;
        insertFreshMovie(tmdbId);

        int concurrency = 8;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = IntStream.range(0, concurrency)
                    .<Future<?>>mapToObj(i -> executor.submit(() -> {
                        ready.countDown();
                        awaitUninterruptibly(start);
                        watchlistService.addToWatchlist(userId, tmdbId, ContentLang.RU);
                    }))
                    .collect(Collectors.toList());

            ready.await();
            start.countDown();

            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdown();
        }

        assertThat(watchlistItemRepository.countByKeyUserId(userId)).isEqualTo(1);
    }

    @Test
    void removeFromWatchlist_entryNeverExisted_doesNotThrow() {
        UUID userId = UUID.randomUUID();

        assertDoesNotThrow(() -> watchlistService.removeFromWatchlist(userId, 999_999L));
    }

    @Test
    void removeFromWatchlist_calledAfterAnotherTransactionalOperation_stillWorks() {
        UUID userId = UUID.randomUUID();
        insertUser(userId, 700_035L);
        long tmdbId = 700_035L;
        insertFreshMovie(tmdbId);
        UserMovieKey key = new UserMovieKey(userId, tmdbId);

        watchlistService.addToWatchlist(userId, tmdbId, ContentLang.RU);

        assertDoesNotThrow(() -> watchlistService.removeFromWatchlist(userId, tmdbId));
        assertThat(watchlistItemRepository.existsById(key)).isFalse();
    }

    @Test
    void markSeen_thenUnmarkSeen_movesEntryBetweenListsAndNeverRestoresWatchlist() {
        UUID userId = UUID.randomUUID();
        insertUser(userId, 700_030L);
        long tmdbId = 700_030L;
        insertFreshMovie(tmdbId);
        UserMovieKey key = new UserMovieKey(userId, tmdbId);

        watchlistService.addToWatchlist(userId, tmdbId, ContentLang.RU);
        assertThat(watchlistItemRepository.existsById(key)).isTrue();

        watchlistService.markSeen(userId, tmdbId, ContentLang.RU);
        assertThat(seenItemRepository.existsById(key)).isTrue();
        assertThat(watchlistItemRepository.existsById(key)).isFalse();

        watchlistService.unmarkSeen(userId, tmdbId);
        assertThat(seenItemRepository.existsById(key)).isFalse();
        assertThat(watchlistItemRepository.existsById(key)).isFalse();
    }

    @Test
    void unmarkSeen_entryNeverExisted_doesNotThrow() {
        UUID userId = UUID.randomUUID();

        assertDoesNotThrow(() -> watchlistService.unmarkSeen(userId, 999_998L));
    }

    @Test
    void getFlags_fiftyMovies_runsExactlyTwoQueries_notOnePerMovie() {
        UUID userId = UUID.randomUUID();
        insertUser(userId, 700_040L);
        List<Long> tmdbIds = LongStream.rangeClosed(700_100, 700_149).boxed().collect(Collectors.toList());
        tmdbIds.forEach(this::insertFreshMovie);
        watchlistService.addToWatchlist(userId, tmdbIds.get(0), ContentLang.RU);
        watchlistService.markSeen(userId, tmdbIds.get(1), ContentLang.RU);

        Statistics statistics =
                entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        Map<Long, MovieFlags> flags = watchlistService.getFlags(userId, tmdbIds);

        assertThat(flags).hasSize(50);
        assertThat(statistics.getPrepareStatementCount())
                .as("getFlags() for 50 ids must run exactly 2 queries (one per list), not one per id")
                .isEqualTo(2);
    }

    @Test
    void getFlags_fourCombinations_matchRealDatabaseState() {
        UUID userId = UUID.randomUUID();
        insertUser(userId, 700_050L);
        long nowhereId = 700_051L;
        long inWatchlistId = 700_052L;
        long seenId = 700_053L;
        long bothId = 700_054L;
        List.of(nowhereId, inWatchlistId, seenId, bothId).forEach(this::insertFreshMovie);

        watchlistService.addToWatchlist(userId, inWatchlistId, ContentLang.RU);
        watchlistService.markSeen(userId, seenId, ContentLang.RU);
        watchlistService.addToWatchlist(userId, bothId, ContentLang.RU);
        jdbcClient
                .sql("INSERT INTO seen_items (user_id, tmdb_id, marked_at) VALUES (:userId, :tmdbId, :markedAt)")
                .param("userId", userId)
                .param("tmdbId", bothId)
                .param("markedAt", Timestamp.from(Instant.now()))
                .update();

        Map<Long, MovieFlags> flags =
                watchlistService.getFlags(userId, List.of(nowhereId, inWatchlistId, seenId, bothId));

        assertThat(flags)
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        nowhereId, new MovieFlags(false, false),
                        inWatchlistId, new MovieFlags(true, false),
                        seenId, new MovieFlags(false, true),
                        bothId, new MovieFlags(true, true)));
    }

    @Test
    void removeAndMarkSeen_fiveUsers_threeHadItInWatchlist_allFiveEndUpSeen_noneInWatchlist() {
        List<UUID> users =
                List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        for (int i = 0; i < users.size(); i++) {
            insertUser(users.get(i), 700_200L + i);
        }
        long tmdbId = 700_210L;
        insertFreshMovie(tmdbId);
        watchlistService.addToWatchlist(users.get(0), tmdbId, ContentLang.RU);
        watchlistService.addToWatchlist(users.get(1), tmdbId, ContentLang.RU);
        watchlistService.addToWatchlist(users.get(2), tmdbId, ContentLang.RU);

        watchlistService.removeAndMarkSeen(users, tmdbId);

        for (UUID userId : users) {
            UserMovieKey key = new UserMovieKey(userId, tmdbId);
            assertThat(seenItemRepository.existsById(key))
                    .as("user %s should be marked as having seen %s", userId, tmdbId)
                    .isTrue();
            assertThat(watchlistItemRepository.existsById(key))
                    .as("user %s should no longer have %s in their watchlist", userId, tmdbId)
                    .isFalse();
        }
    }

    @Test
    void removeAndMarkSeen_calledTwice_doesNotThrow() {
        List<UUID> users = List.of(UUID.randomUUID(), UUID.randomUUID());
        insertUser(users.get(0), 700_220L);
        insertUser(users.get(1), 700_221L);
        long tmdbId = 700_211L;
        insertFreshMovie(tmdbId);
        watchlistService.addToWatchlist(users.get(0), tmdbId, ContentLang.RU);

        watchlistService.removeAndMarkSeen(users, tmdbId);

        assertDoesNotThrow(() -> watchlistService.removeAndMarkSeen(users, tmdbId));
        for (UUID userId : users) {
            assertThat(seenItemRepository.existsById(new UserMovieKey(userId, tmdbId)))
                    .isTrue();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
