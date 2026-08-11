package app.quorum.session;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.auth.api.TelegramProfile;
import app.quorum.common.SessionState;
import app.quorum.session.domain.DeckResult;
import app.quorum.session.domain.ScoredMovie;
import app.quorum.session.domain.Session;
import app.quorum.session.domain.SessionExpirationJob;
import app.quorum.session.domain.SessionParams;
import app.quorum.session.infra.SessionRepository;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SessionExpirationJobIT {

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
    private SessionRepository sessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

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

    private static DeckResult deckFor(long tmdbId) {
        return new DeckResult(List.of(new ScoredMovie(tmdbId, new BigDecimal("9.0"), List.of())), "v1");
    }

    private static User user(long tgUserId) {
        return User.registerFromTelegram(
                new TelegramProfile(tgUserId, "User " + tgUserId, "user" + tgUserId, null, "ru"), Clock.systemUTC());
    }

    private static SessionParams params() {
        return new SessionParams("Movie night", 10, 30, false);
    }

    @Test
    void run_lobbyOlderThanSevenDays_transitionsToExpired() {
        User host = userRepository.save(user(1000));
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        Session session = Session.create(host.getId(), params(), "EXPIRE001", Clock.fixed(base, ZoneOffset.UTC));
        sessionRepository.saveAndFlush(session);

        Clock future = Clock.fixed(base.plus(Duration.ofDays(8)), ZoneOffset.UTC);
        SessionExpirationJob job = new SessionExpirationJob(sessionRepository, future, transactionManager);

        job.run();

        Session reloaded = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(SessionState.EXPIRED);
    }

    @Test
    void run_lobbyYoungerThanSevenDays_isUntouched() {
        User host = userRepository.save(user(1001));
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        Session session = Session.create(host.getId(), params(), "EXPIRE002", Clock.fixed(base, ZoneOffset.UTC));
        sessionRepository.saveAndFlush(session);

        Clock stillFresh = Clock.fixed(base.plus(Duration.ofDays(1)), ZoneOffset.UTC);
        SessionExpirationJob job = new SessionExpirationJob(sessionRepository, stillFresh, transactionManager);

        job.run();

        Session reloaded = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(SessionState.LOBBY);
    }

    @Test
    void run_decidedOlderThanSevenDays_transitionsToArchived() {
        User host = userRepository.save(user(1004));
        User member = userRepository.save(user(1005));
        insertMovie(800_002);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Clock startClock = Clock.fixed(start, ZoneOffset.UTC);
        Session session = Session.create(host.getId(), params(), "EXPIRE004", startClock);
        session.join(member.getId(), startClock);
        session.startVoting(host.getId(), deckFor(800_002), "v1", startClock);
        session.decide(800_002L, false, startClock);
        sessionRepository.saveAndFlush(session);

        Clock future = Clock.fixed(start.plus(Duration.ofDays(8)), ZoneOffset.UTC);
        SessionExpirationJob job = new SessionExpirationJob(sessionRepository, future, transactionManager);

        job.run();

        Session reloaded = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(SessionState.ARCHIVED);
    }

    @Test
    void run_oneSessionFailsToProcess_othersInTheSameBatchStillGetExpired() {
        User host = userRepository.save(user(1006));
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        Clock baseClock = Clock.fixed(base, ZoneOffset.UTC);
        Session healthy = Session.create(host.getId(), params(), "EXPIRE005", baseClock);
        Session broken = Session.create(host.getId(), params(), "EXPIRE006", baseClock);
        sessionRepository.saveAll(List.of(healthy, broken));

        SessionRepository flaky =
                Mockito.mock(SessionRepository.class, AdditionalAnswers.delegatesTo(sessionRepository));
        Mockito.doThrow(new RuntimeException("simulated failure")).when(flaky).lockById(broken.getId());

        Clock future = Clock.fixed(base.plus(Duration.ofDays(8)), ZoneOffset.UTC);
        SessionExpirationJob job = new SessionExpirationJob(flaky, future, transactionManager);

        job.run();

        assertThat(sessionRepository.findById(healthy.getId()).orElseThrow().getState())
                .isEqualTo(SessionState.EXPIRED);
        assertThat(sessionRepository.findById(broken.getId()).orElseThrow().getState())
                .isEqualTo(SessionState.LOBBY);
    }
}
