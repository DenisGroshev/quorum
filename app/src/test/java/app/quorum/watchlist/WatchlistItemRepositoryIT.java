package app.quorum.watchlist;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.auth.api.TelegramProfile;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import app.quorum.watchlist.domain.UserMovieKey;
import app.quorum.watchlist.domain.WatchlistItem;
import app.quorum.watchlist.infra.WatchlistItemRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WatchlistItemRepositoryIT {

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
    private WatchlistItemRepository watchlistItemRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcClient jdbcClient;

    private static User user(long tgUserId) {
        return User.registerFromTelegram(
                new TelegramProfile(tgUserId, "User " + tgUserId, "user" + tgUserId, null, "ru"), Clock.systemUTC());
    }

    private void insertMovie(long tmdbId) {
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
                .param("syncedAt", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")))
                .update();
    }

    @Test
    void saveAndRead_roundTrips() {
        User owner = userRepository.save(user(1));
        insertMovie(603);
        Instant addedAt = Instant.parse("2026-02-01T10:00:00Z");

        watchlistItemRepository.save(new WatchlistItem(owner.getId(), 603L, Clock.fixed(addedAt, ZoneOffset.UTC)));

        UserMovieKey key = new UserMovieKey(owner.getId(), 603L);
        WatchlistItem loaded = watchlistItemRepository.findById(key).orElseThrow();
        assertThat(loaded.getAddedAt()).isEqualTo(addedAt);
        assertThat(watchlistItemRepository.existsById(key)).isTrue();
        assertThat(watchlistItemRepository.countByKeyUserId(owner.getId())).isEqualTo(1);
    }

    @Test
    void findByKeyUserIdOrderByAddedAtDesc_returnsNewestFirst_andIsolatesOtherUsers() {
        User owner = userRepository.save(user(2));
        User other = userRepository.save(user(3));
        insertMovie(701);
        insertMovie(702);
        insertMovie(703);

        watchlistItemRepository.save(new WatchlistItem(
                owner.getId(), 701L, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)));
        watchlistItemRepository.save(new WatchlistItem(
                owner.getId(), 702L, Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC)));
        watchlistItemRepository.save(new WatchlistItem(
                owner.getId(), 703L, Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC)));
        watchlistItemRepository.save(new WatchlistItem(other.getId(), 701L, Clock.systemUTC()));

        Page<WatchlistItem> page =
                watchlistItemRepository.findByKeyUserIdOrderByAddedAtDesc(owner.getId(), PageRequest.of(0, 2));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(w -> w.getKey().tmdbId()).containsExactly(702L, 703L);
    }

    @Test
    void findByKeyUserIdAndKeyTmdbIdIn_returnsOnlyRequestedMovies() {
        User owner = userRepository.save(user(4));
        insertMovie(801);
        insertMovie(802);
        insertMovie(803);
        Clock clock = Clock.systemUTC();
        watchlistItemRepository.save(new WatchlistItem(owner.getId(), 801L, clock));
        watchlistItemRepository.save(new WatchlistItem(owner.getId(), 802L, clock));

        List<WatchlistItem> found =
                watchlistItemRepository.findByKeyUserIdAndKeyTmdbIdIn(owner.getId(), List.of(801L, 803L));

        assertThat(found).extracting(w -> w.getKey().tmdbId()).containsExactly(801L);
    }

    @Test
    void deleteById_removesItem() {
        User owner = userRepository.save(user(5));
        insertMovie(901);
        watchlistItemRepository.save(new WatchlistItem(owner.getId(), 901L, Clock.systemUTC()));
        UserMovieKey key = new UserMovieKey(owner.getId(), 901L);

        watchlistItemRepository.deleteById(key);

        assertThat(watchlistItemRepository.existsById(key)).isFalse();
    }
}
