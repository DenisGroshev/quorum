package app.quorum.voting;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.auth.api.TelegramProfile;
import app.quorum.common.SessionState;
import app.quorum.session.api.SessionGuard;
import app.quorum.session.domain.DeckResult;
import app.quorum.session.domain.ScoredMovie;
import app.quorum.session.domain.Session;
import app.quorum.session.domain.SessionParams;
import app.quorum.session.infra.SessionRepository;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import app.quorum.voting.domain.VoteValue;
import app.quorum.voting.domain.VotingDeadlineJob;
import app.quorum.voting.domain.VotingService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
class VotingDeadlineJobIT {

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
    private SessionGuard sessionGuard;

    @Autowired
    private VotingService votingService;

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
    void run_votingDeadlinePassed_finishesVotingWithNoVotesAsExpired() {
        User host = userRepository.save(user(1100));
        User member = userRepository.save(user(1101));
        insertMovie(900_001);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Clock startClock = Clock.fixed(start, ZoneOffset.UTC);
        Session session = Session.create(host.getId(), params(), "DDLN001", startClock);
        session.join(member.getId(), startClock);
        session.startVoting(host.getId(), deckFor(900_001), "v1", startClock);
        sessionRepository.saveAndFlush(session);

        Clock afterDeadline = Clock.fixed(start.plus(Duration.ofMinutes(31)), ZoneOffset.UTC);
        VotingDeadlineJob job = new VotingDeadlineJob(sessionGuard, votingService, afterDeadline);

        job.run();

        Session reloaded = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(SessionState.EXPIRED);
    }

    @Test
    void run_votingDeadlinePassed_finishesVotingWithVotesAsDecided() {
        User host = userRepository.save(user(1102));
        User member = userRepository.save(user(1103));
        insertMovie(900_002);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Clock startClock = Clock.fixed(start, ZoneOffset.UTC);
        Session session = Session.create(host.getId(), params(), "DDLN002", startClock);
        session.join(member.getId(), startClock);
        session.startVoting(host.getId(), deckFor(900_002), "v1", startClock);
        sessionRepository.saveAndFlush(session);
        votingService.castVote(session.getId(), host.getId(), 900_002L, VoteValue.LIKE);

        Clock afterDeadline = Clock.fixed(start.plus(Duration.ofMinutes(31)), ZoneOffset.UTC);
        VotingDeadlineJob job = new VotingDeadlineJob(sessionGuard, votingService, afterDeadline);

        job.run();

        Session reloaded = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(SessionState.DECIDED);
        assertThat(reloaded.getWinnerTmdbId()).isEqualTo(900_002L);
    }

    @Test
    void run_deadlineNotYetPassed_isUntouched() {
        User host = userRepository.save(user(1104));
        User member = userRepository.save(user(1105));
        insertMovie(900_003);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Clock startClock = Clock.fixed(start, ZoneOffset.UTC);
        Session session = Session.create(host.getId(), params(), "DDLN003", startClock);
        session.join(member.getId(), startClock);
        session.startVoting(host.getId(), deckFor(900_003), "v1", startClock);
        sessionRepository.saveAndFlush(session);

        Clock stillWithinDeadline = Clock.fixed(start.plus(Duration.ofMinutes(5)), ZoneOffset.UTC);
        VotingDeadlineJob job = new VotingDeadlineJob(sessionGuard, votingService, stillWithinDeadline);

        job.run();

        Session reloaded = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(SessionState.VOTING);
    }
}
