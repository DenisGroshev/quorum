package app.quorum.session;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.auth.api.TelegramProfile;
import app.quorum.session.api.SessionHistoryQueries;
import app.quorum.session.domain.DeckResult;
import app.quorum.session.domain.ScoredMovie;
import app.quorum.session.domain.Session;
import app.quorum.session.domain.SessionParams;
import app.quorum.session.infra.SessionRepository;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
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
class SessionHistoryQueriesIT {

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
    private SessionHistoryQueries sessionHistoryQueries;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcClient jdbcClient;

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

    private static User user(long tgUserId) {
        return User.registerFromTelegram(
                new TelegramProfile(tgUserId, "User " + tgUserId, "user" + tgUserId, null, "ru"), Clock.systemUTC());
    }

    private static SessionParams params() {
        return new SessionParams("Movie night", 10, 30, false);
    }

    private static DeckResult deckFor(long tmdbId) {
        return new DeckResult(List.of(new ScoredMovie(tmdbId, new BigDecimal("9.0"), List.of())), "v1");
    }

    private Session watchedSessionWith(long winnerTmdbId, String code, Clock clock, User host, User... members) {
        Session session = Session.create(host.getId(), params(), code, clock);
        for (User member : members) {
            session.join(member.getId(), clock);
        }
        session.startVoting(host.getId(), deckFor(winnerTmdbId), "v1", clock);
        session.decide(winnerTmdbId, false, clock);
        return session;
    }

    @Test
    void pastWinnersForExactMemberSet_matchesOnlySessionsWithTheExactSameParticipants() {
        User alice = userRepository.save(user(3000));
        User bob = userRepository.save(user(3001));
        User carol = userRepository.save(user(3002));
        User dave = userRepository.save(user(3003));
        insertMovie(950_001);
        insertMovie(950_002);
        insertMovie(950_003);
        Clock clock = Clock.systemUTC();

        Session exactMatch = watchedSessionWith(950_001, "HIST0001", clock, alice, bob);
        Session superset = watchedSessionWith(950_002, "HIST0002", clock, alice, bob, carol);
        Session differentComposition = watchedSessionWith(950_003, "HIST0003", clock, alice, dave);
        sessionRepository.saveAll(List.of(exactMatch, superset, differentComposition));

        Set<Long> winners = sessionHistoryQueries.pastWinnersForExactMemberSet(Set.of(alice.getId(), bob.getId()));

        assertThat(winners).containsExactly(950_001L);
    }

    @Test
    void pastWinnersForExactMemberSet_ignoresSessionsWithoutADecidedWinner() {
        User alice = userRepository.save(user(3010));
        User bob = userRepository.save(user(3011));
        Session lobby = Session.create(alice.getId(), params(), "HIST0004", Clock.systemUTC());
        lobby.join(bob.getId(), Clock.systemUTC());
        sessionRepository.saveAndFlush(lobby);

        Set<Long> winners = sessionHistoryQueries.pastWinnersForExactMemberSet(Set.of(alice.getId(), bob.getId()));

        assertThat(winners).isEmpty();
    }

    @Test
    void pastWinnersForExactMemberSet_deadlinePassedWithoutRealWinner_isExcluded() {
        User alice = userRepository.save(user(3020));
        User bob = userRepository.save(user(3021));
        insertMovie(950_004);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Clock startClock = Clock.fixed(start, ZoneOffset.UTC);
        Session session = Session.create(alice.getId(), params(), "HIST0005", startClock);
        session.join(bob.getId(), startClock);
        session.startVoting(alice.getId(), deckFor(950_004), "v1", startClock);
        session.decide(null, false, startClock);
        sessionRepository.saveAndFlush(session);

        Set<Long> winners = sessionHistoryQueries.pastWinnersForExactMemberSet(Set.of(alice.getId(), bob.getId()));

        assertThat(winners).isEmpty();
    }

    @Test
    void pastWinnersForExactMemberSet_emptyMemberSet_returnsEmptyWithoutQueryError() {
        assertThat(sessionHistoryQueries.pastWinnersForExactMemberSet(Set.of())).isEmpty();
    }
}
