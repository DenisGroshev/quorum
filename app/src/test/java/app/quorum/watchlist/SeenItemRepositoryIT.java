package app.quorum.watchlist;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.auth.api.TelegramProfile;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import app.quorum.watchlist.domain.SeenItem;
import app.quorum.watchlist.domain.UserMovieKey;
import app.quorum.watchlist.infra.SeenItemRepository;
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

// Фильмы заводятся через сырой INSERT (JdbcClient) — см. WatchlistItemRepositoryIT:
// у watchlist нет доступа к catalog.domain/infra (CatalogModuleArchitectureTest).
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SeenItemRepositoryIT {

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
    private SeenItemRepository seenItemRepository;

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
        User owner = userRepository.save(user(101));
        insertMovie(1101);
        Instant markedAt = Instant.parse("2026-02-01T10:00:00Z");

        seenItemRepository.save(new SeenItem(owner.getId(), 1101L, Clock.fixed(markedAt, ZoneOffset.UTC)));

        UserMovieKey key = new UserMovieKey(owner.getId(), 1101L);
        SeenItem loaded = seenItemRepository.findById(key).orElseThrow();
        assertThat(loaded.getMarkedAt()).isEqualTo(markedAt);
        assertThat(seenItemRepository.existsById(key)).isTrue();
        assertThat(seenItemRepository.countByKeyUserId(owner.getId())).isEqualTo(1);
    }

    @Test
    void findByKeyUserIdOrderByMarkedAtDesc_returnsNewestFirst() {
        User owner = userRepository.save(user(102));
        insertMovie(1201);
        insertMovie(1202);
        seenItemRepository.save(
                new SeenItem(owner.getId(), 1201L, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)));
        seenItemRepository.save(
                new SeenItem(owner.getId(), 1202L, Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC)));

        Page<SeenItem> page =
                seenItemRepository.findByKeyUserIdOrderByMarkedAtDesc(owner.getId(), PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(s -> s.getKey().tmdbId()).containsExactly(1202L, 1201L);
    }

    @Test
    void findByKeyUserIdAndKeyTmdbIdIn_andDeleteById_work() {
        User owner = userRepository.save(user(103));
        insertMovie(1301);
        insertMovie(1302);
        Clock clock = Clock.systemUTC();
        seenItemRepository.save(new SeenItem(owner.getId(), 1301L, clock));
        seenItemRepository.save(new SeenItem(owner.getId(), 1302L, clock));

        List<SeenItem> found = seenItemRepository.findByKeyUserIdAndKeyTmdbIdIn(owner.getId(), List.of(1301L, 9_999L));
        assertThat(found).extracting(s -> s.getKey().tmdbId()).containsExactly(1301L);

        UserMovieKey key = new UserMovieKey(owner.getId(), 1301L);
        seenItemRepository.deleteById(key);
        assertThat(seenItemRepository.existsById(key)).isFalse();
    }
}
