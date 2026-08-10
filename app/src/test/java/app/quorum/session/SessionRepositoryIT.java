package app.quorum.session;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.auth.api.TelegramProfile;
import app.quorum.common.SessionState;
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
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SessionRepositoryIT {

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

    @Test
    void saveAndRead_roundTrips() {
        User host = userRepository.save(user(1));
        Instant createdAt = Instant.parse("2026-01-01T10:00:00Z");

        Session session = Session.create(host.getId(), params(), "ABCD1234", Clock.fixed(createdAt, ZoneOffset.UTC));
        sessionRepository.save(session);

        Session loaded = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(loaded.getHostId()).isEqualTo(host.getId());
        assertThat(loaded.getState()).isEqualTo(SessionState.LOBBY);
        assertThat(loaded.getInviteCode()).isEqualTo("ABCD1234");
        assertThat(loaded.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    @Transactional
    void lockById_insideTransaction_returnsSession() {
        User host = userRepository.save(user(2));
        Session session = Session.create(host.getId(), params(), "LOCKID01", Clock.systemUTC());
        sessionRepository.saveAndFlush(session);

        Optional<Session> locked = sessionRepository.lockById(session.getId());

        assertThat(locked).isPresent();
        assertThat(locked.get().getId()).isEqualTo(session.getId());
    }

    @Test
    @Transactional
    void lockByInviteCode_insideTransaction_returnsSession() {
        User host = userRepository.save(user(3));
        Session session = Session.create(host.getId(), params(), "INVCODE1", Clock.systemUTC());
        sessionRepository.saveAndFlush(session);

        Optional<Session> locked = sessionRepository.lockByInviteCode("INVCODE1");

        assertThat(locked).isPresent();
        assertThat(locked.get().getId()).isEqualTo(session.getId());
    }

    @Test
    void countByHostIdAndStateIn_countsOnlyMatchingStatesForThatHost() {
        User host = userRepository.save(user(4));
        User otherHost = userRepository.save(user(5));
        Clock clock = Clock.systemUTC();
        Session lobby1 = Session.create(host.getId(), params(), "CNT00001", clock);
        Session lobby2 = Session.create(host.getId(), params(), "CNT00002", clock);
        Session cancelled = Session.create(host.getId(), params(), "CNT00003", clock);
        cancelled.cancel(host.getId());
        Session othersLobby = Session.create(otherHost.getId(), params(), "CNT00004", clock);
        sessionRepository.saveAll(List.of(lobby1, lobby2, cancelled, othersLobby));

        long count = sessionRepository.countByHostIdAndStateIn(
                host.getId(), Set.of(SessionState.LOBBY, SessionState.VOTING));

        assertThat(count).isEqualTo(2);
    }

    @Test
    void findByStateAndCreatedAtBefore_findsOnlyOlderLobbySessions() {
        User host = userRepository.save(user(6));
        Instant old = Instant.parse("2026-01-01T00:00:00Z");
        Instant recent = Instant.parse("2026-06-01T00:00:00Z");
        Instant cutoff = Instant.parse("2026-03-01T00:00:00Z");
        Session oldSession = Session.create(host.getId(), params(), "OLD00001", Clock.fixed(old, ZoneOffset.UTC));
        Session recentSession = Session.create(host.getId(), params(), "NEW00001", Clock.fixed(recent, ZoneOffset.UTC));
        sessionRepository.saveAll(List.of(oldSession, recentSession));

        List<Session> expired = sessionRepository.findByStateAndCreatedAtBefore(SessionState.LOBBY, cutoff);

        assertThat(expired).extracting(Session::getId).containsExactly(oldSession.getId());
    }

    @Test
    void findByStateAndVotingDeadlineAtBefore_findsOnlyOverdueVotingSessions() {
        User host = userRepository.save(user(7));
        User member = userRepository.save(user(8));
        insertMovie(500_001);
        Instant cutoff = Instant.parse("2026-01-01T00:31:00Z");

        Instant overdueStart = Instant.parse("2026-01-01T00:00:00Z");
        Session overdue = Session.create(host.getId(), params(), "VOTE0001", Clock.fixed(overdueStart, ZoneOffset.UTC));
        overdue.join(member.getId(), Clock.fixed(overdueStart, ZoneOffset.UTC));
        overdue.startVoting(host.getId(), deckFor(500_001L), "v1", Clock.fixed(overdueStart, ZoneOffset.UTC));

        Instant recentStart = Instant.parse("2026-01-01T00:25:00Z");
        Session stillOpen =
                Session.create(host.getId(), params(), "VOTE0002", Clock.fixed(recentStart, ZoneOffset.UTC));
        stillOpen.join(member.getId(), Clock.fixed(recentStart, ZoneOffset.UTC));
        stillOpen.startVoting(host.getId(), deckFor(500_001L), "v1", Clock.fixed(recentStart, ZoneOffset.UTC));

        sessionRepository.saveAll(List.of(overdue, stillOpen));

        List<Session> expired = sessionRepository.findByStateAndVotingDeadlineAtBefore(SessionState.VOTING, cutoff);

        assertThat(expired).extracting(Session::getId).containsExactly(overdue.getId());
    }

    @Test
    void findByStateAndDecidedAtBefore_findsOnlyOldDecidedSessions() {
        User host = userRepository.save(user(9));
        User member = userRepository.save(user(10));
        insertMovie(500_002);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant cutoff = Instant.parse("2026-01-05T00:00:00Z");

        Session old = Session.create(host.getId(), params(), "DEC00001", Clock.fixed(start, ZoneOffset.UTC));
        old.join(member.getId(), Clock.fixed(start, ZoneOffset.UTC));
        old.startVoting(host.getId(), deckFor(500_002L), "v1", Clock.fixed(start, ZoneOffset.UTC));
        old.decide(500_002L, false, Clock.fixed(Instant.parse("2026-01-02T00:00:00Z"), ZoneOffset.UTC));

        Session recent = Session.create(host.getId(), params(), "DEC00002", Clock.fixed(start, ZoneOffset.UTC));
        recent.join(member.getId(), Clock.fixed(start, ZoneOffset.UTC));
        recent.startVoting(host.getId(), deckFor(500_002L), "v1", Clock.fixed(start, ZoneOffset.UTC));
        recent.decide(500_002L, false, Clock.fixed(Instant.parse("2026-01-06T00:00:00Z"), ZoneOffset.UTC));

        sessionRepository.saveAll(List.of(old, recent));

        List<Session> expired = sessionRepository.findByStateAndDecidedAtBefore(SessionState.DECIDED, cutoff);

        assertThat(expired).extracting(Session::getId).containsExactly(old.getId());
    }

    @Test
    void findByMembersKeyUserId_returnsSessionsWhereUserIsMember_newestFirst() {
        User host = userRepository.save(user(11));
        User stranger = userRepository.save(user(12));
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        Instant second = Instant.parse("2026-02-01T00:00:00Z");
        Session sessionA = Session.create(host.getId(), params(), "MEM00001", Clock.fixed(first, ZoneOffset.UTC));
        Session sessionB = Session.create(host.getId(), params(), "MEM00002", Clock.fixed(second, ZoneOffset.UTC));
        Session strangersSession = Session.create(stranger.getId(), params(), "MEM00003", Clock.systemUTC());
        sessionRepository.saveAll(List.of(sessionA, sessionB, strangersSession));

        List<Session> found = sessionRepository.findByMembers_Key_UserIdOrderByCreatedAtDesc(host.getId());

        assertThat(found).extracting(Session::getId).containsExactly(sessionB.getId(), sessionA.getId());
    }
}
