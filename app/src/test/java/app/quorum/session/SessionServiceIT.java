package app.quorum.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.quorum.auth.api.TelegramProfile;
import app.quorum.common.SessionState;
import app.quorum.common.exception.DomainRuleException;
import app.quorum.common.exception.SessionForbiddenException;
import app.quorum.common.exception.SessionNotFoundException;
import app.quorum.common.exception.SessionStateConflictException;
import app.quorum.session.api.SessionCancelledEvent;
import app.quorum.session.api.VotingStartedEvent;
import app.quorum.session.domain.DeckBuilder;
import app.quorum.session.domain.DeckResult;
import app.quorum.session.domain.InviteCodeGenerator;
import app.quorum.session.domain.ScoredMovie;
import app.quorum.session.domain.Session;
import app.quorum.session.domain.SessionCreationResult;
import app.quorum.session.domain.SessionParams;
import app.quorum.session.domain.SessionService;
import app.quorum.session.infra.SessionRepository;
import app.quorum.session.web.dto.CreateSessionRequest;
import app.quorum.session.web.dto.UpdateSessionParamsRequest;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SessionServiceIT {

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
    private SessionService sessionService;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private DeckBuilder deckBuilder;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private ConfigurableApplicationContext applicationContext;

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

    private long memberCount(UUID sessionId) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM session_members WHERE session_id = :sessionId")
                .param("sessionId", sessionId)
                .query(Long.class)
                .single();
    }

    private long deckItemCount(UUID sessionId) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM deck_items WHERE session_id = :sessionId")
                .param("sessionId", sessionId)
                .query(Long.class)
                .single();
    }

    private BigDecimal deckItemScore(UUID sessionId, long tmdbId) {
        return jdbcClient
                .sql("SELECT score FROM deck_items WHERE session_id = :sessionId AND tmdb_id = :tmdbId")
                .param("sessionId", sessionId)
                .param("tmdbId", tmdbId)
                .query(BigDecimal.class)
                .single();
    }

    private String deckItemReasons(UUID sessionId, long tmdbId) {
        return jdbcClient
                .sql("SELECT reasons::text FROM deck_items WHERE session_id = :sessionId AND tmdb_id = :tmdbId")
                .param("sessionId", sessionId)
                .param("tmdbId", tmdbId)
                .query(String.class)
                .single();
    }

    private void addToWatchlist(UUID userId, long tmdbId) {
        jdbcClient
                .sql("INSERT INTO watchlist_items (user_id, tmdb_id, added_at) VALUES (:userId, :tmdbId, :addedAt)")
                .param("userId", userId)
                .param("tmdbId", tmdbId)
                .param("addedAt", Timestamp.from(Instant.now()))
                .update();
    }

    private boolean isInWatchlist(UUID userId, long tmdbId) {
        return jdbcClient
                        .sql("SELECT COUNT(*) FROM watchlist_items WHERE user_id = :userId AND tmdb_id = :tmdbId")
                        .param("userId", userId)
                        .param("tmdbId", tmdbId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    private boolean isSeen(UUID userId, long tmdbId) {
        return jdbcClient
                        .sql("SELECT COUNT(*) FROM seen_items WHERE user_id = :userId AND tmdb_id = :tmdbId")
                        .param("userId", userId)
                        .param("tmdbId", tmdbId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    private Integer memberRating(UUID sessionId, UUID userId) {
        return jdbcClient
                .sql("SELECT rating FROM session_members WHERE session_id = :sessionId AND user_id = :userId")
                .param("sessionId", sessionId)
                .param("userId", userId)
                .query(Integer.class)
                .single();
    }

    private boolean isMemberInDb(UUID sessionId, UUID userId) {
        return jdbcClient
                        .sql("SELECT COUNT(*) FROM session_members WHERE session_id = :sessionId AND user_id = :userId")
                        .param("sessionId", sessionId)
                        .param("userId", userId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    private Callable<Exception> joinAttempt(
            CountDownLatch ready, CountDownLatch start, String inviteCode, UUID userId) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                sessionService.join(inviteCode, userId);
                return null;
            } catch (Exception e) {
                return e;
            }
        };
    }

    private static User user(long tgUserId) {
        return User.registerFromTelegram(
                new TelegramProfile(tgUserId, "User " + tgUserId, "user" + tgUserId, null, "ru"), Clock.systemUTC());
    }

    private static CreateSessionRequest defaultRequest() {
        return new CreateSessionRequest(null, null, null, false);
    }

    private static SessionParams params() {
        return new SessionParams("Movie night", 10, 30, false);
    }

    private static UpdateSessionParamsRequest updateRequest() {
        return new UpdateSessionParamsRequest("Movie night", 10, 30, false);
    }

    private static final class SequencedInviteCodeGenerator extends InviteCodeGenerator {
        private final Iterator<String> codes;

        SequencedInviteCodeGenerator(String... codes) {
            this.codes = List.of(codes).iterator();
        }

        @Override
        public String generate() {
            return codes.next();
        }
    }

    @Test
    void create_defaultRequest_hostBecomesHostAndMember() {
        User host = userRepository.save(user(1));

        SessionCreationResult result = sessionService.create(host.getId(), defaultRequest());

        Session session = result.session();
        assertThat(session.getHostId()).isEqualTo(host.getId());
        assertThat(session.getState()).isEqualTo(SessionState.LOBBY);
        assertThat(session.isMember(host.getId())).isTrue();
        assertThat(session.getMembers()).hasSize(1);
    }

    @Test
    void create_returnsInviteCodeAndLink() {
        User host = userRepository.save(user(2));

        SessionCreationResult result = sessionService.create(host.getId(), defaultRequest());

        assertThat(result.session().getInviteCode()).hasSize(10);
        assertThat(result.inviteLink()).startsWith("https://t.me/");
        assertThat(result.inviteLink()).contains(result.session().getInviteCode());
    }

    @Test
    void create_nullFields_appliesDefaults() {
        User host = userRepository.save(user(3));

        SessionCreationResult result = sessionService.create(host.getId(), defaultRequest());

        Session session = result.session();
        assertThat(session.getTitle()).startsWith("Киновечер ");
        assertThat(session.getDeckSize()).isEqualTo((short) 20);
        assertThat(session.getVoteDeadlineMinutes()).isEqualTo(120);
        assertThat(session.isWatchlistOnly()).isFalse();
    }

    @Test
    void create_sixthActiveSessionForHost_throwsHostLimitError() {
        User host = userRepository.save(user(4));
        for (int i = 0; i < 5; i++) {
            sessionService.create(host.getId(), defaultRequest());
        }

        assertThatThrownBy(() -> sessionService.create(host.getId(), defaultRequest()))
                .isInstanceOf(DomainRuleException.class)
                .hasMessage("session.host-limit");
    }

    @Test
    void create_invalidDeckSize_throwsDomainRuleException_notDbConstraintError() {
        User host = userRepository.save(user(5));

        assertThatThrownBy(() -> sessionService.create(host.getId(), new CreateSessionRequest(null, 15, null, false)))
                .isInstanceOf(DomainRuleException.class)
                .hasMessage("validation.failed");
    }

    @Test
    void create_invalidVoteDeadlineMinutes_throwsDomainRuleException() {
        User host = userRepository.save(user(6));

        assertThatThrownBy(() -> sessionService.create(host.getId(), new CreateSessionRequest(null, null, 45, false)))
                .isInstanceOf(DomainRuleException.class)
                .hasMessage("validation.failed");
    }

    @Test
    void create_inviteCodeCollidesOnce_retriesWithFreshCode() {
        User host = userRepository.save(user(7));
        Session existing = Session.create(host.getId(), params(), "AAAAAAAAAA", Clock.systemUTC());
        sessionRepository.saveAndFlush(existing);

        SessionService serviceWithStubbedCodes = new SessionService(
                sessionRepository,
                new SequencedInviteCodeGenerator("AAAAAAAAAA", "BBBBBBBBBB"),
                deckBuilder,
                eventPublisher,
                clock,
                5,
                "quorum_bot",
                "quorum-app",
                transactionManager);

        SessionCreationResult result = serviceWithStubbedCodes.create(host.getId(), defaultRequest());

        assertThat(result.session().getInviteCode()).isEqualTo("BBBBBBBBBB");
    }

    @Test
    void join_validInviteCode_addsUserAsMember() {
        User host = userRepository.save(user(10));
        User joiner = userRepository.save(user(11));
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());

        sessionService.join(created.session().getInviteCode(), joiner.getId());

        assertThat(isMemberInDb(created.session().getId(), joiner.getId())).isTrue();
        assertThat(memberCount(created.session().getId())).isEqualTo(2);
    }

    @Test
    void join_alreadyMember_succeedsWithoutDuplication() {
        User host = userRepository.save(user(12));
        User joiner = userRepository.save(user(13));
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());

        sessionService.join(created.session().getInviteCode(), joiner.getId());
        sessionService.join(created.session().getInviteCode(), joiner.getId());

        assertThat(memberCount(created.session().getId())).isEqualTo(2);
    }

    @Test
    void join_invalidInviteCode_throwsSessionNotFound() {
        User someone = userRepository.save(user(14));

        assertThatThrownBy(() -> sessionService.join("NOPE000000", someone.getId()))
                .isInstanceOf(SessionNotFoundException.class)
                .hasMessage("session.not-found");
    }

    @Test
    void join_sessionFull_throwsDomainRuleException() {
        User host = userRepository.save(user(20));
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());
        String inviteCode = created.session().getInviteCode();
        for (int i = 0; i < 9; i++) {
            User member = userRepository.save(user(21 + i));
            sessionService.join(inviteCode, member.getId());
        }
        User eleventh = userRepository.save(user(31));

        assertThatThrownBy(() -> sessionService.join(inviteCode, eleventh.getId()))
                .isInstanceOf(DomainRuleException.class)
                .hasMessage("session.full");
    }

    @Test
    void join_sessionInVoting_throwsStateConflict() {
        User host = userRepository.save(user(40));
        User member = userRepository.save(user(41));
        insertMovie(600_001);
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());
        sessionService.join(created.session().getInviteCode(), member.getId());

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Session session =
                    sessionRepository.findById(created.session().getId()).orElseThrow();
            session.startVoting(host.getId(), deckFor(600_001), "v1", clock);
            sessionRepository.saveAndFlush(session);
        });

        User latecomer = userRepository.save(user(42));

        assertThatThrownBy(() -> sessionService.join(created.session().getInviteCode(), latecomer.getId()))
                .isInstanceOf(SessionStateConflictException.class)
                .hasMessage("session.not-joinable");
    }

    @Test
    void startVoting_hostWithEnoughMembers_movesToVotingWithDeck() {
        User host = userRepository.save(user(50));
        User member = userRepository.save(user(51));
        insertMovie(700_001);
        insertMovie(700_002);
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());
        sessionService.join(created.session().getInviteCode(), member.getId());
        addToWatchlist(host.getId(), 700_001);
        addToWatchlist(member.getId(), 700_001);

        sessionService.startVoting(created.session().getId(), host.getId());

        Session reloaded = sessionRepository.findById(created.session().getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(SessionState.VOTING);
        assertThat(reloaded.getVotingDeadlineAt()).isNotNull();
        assertThat(deckItemCount(created.session().getId())).isGreaterThan(0);
        assertThat(deckItemScore(created.session().getId(), 700_001)).isGreaterThan(BigDecimal.ZERO);
        assertThat(deckItemReasons(created.session().getId(), 700_001)).isNotEqualTo("[]");
    }

    @Test
    void startVoting_publishesVotingStartedEvent() {
        User host = userRepository.save(user(56));
        User member = userRepository.save(user(57));
        insertMovie(700_004);
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());
        sessionService.join(created.session().getInviteCode(), member.getId());
        addToWatchlist(host.getId(), 700_004);
        addToWatchlist(member.getId(), 700_004);
        List<VotingStartedEvent> received = new ArrayList<>();
        ApplicationListener<ApplicationEvent> listener = event -> {
            if (event instanceof PayloadApplicationEvent<?> payloadEvent
                    && payloadEvent.getPayload() instanceof VotingStartedEvent votingStartedEvent) {
                received.add(votingStartedEvent);
            }
        };
        applicationContext.addApplicationListener(listener);

        sessionService.startVoting(created.session().getId(), host.getId());

        assertThat(received)
                .extracting(VotingStartedEvent::sessionId)
                .containsExactly(created.session().getId());
    }

    @Test
    void startVoting_nonHostMember_throwsForbidden() {
        User host = userRepository.save(user(52));
        User member = userRepository.save(user(53));
        insertMovie(700_003);
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());
        sessionService.join(created.session().getInviteCode(), member.getId());

        assertThatThrownBy(() -> sessionService.startVoting(created.session().getId(), member.getId()))
                .isInstanceOf(SessionForbiddenException.class)
                .hasMessage("session.forbidden");
    }

    @Test
    void startVoting_notEnoughMembers_throwsDomainRuleException() {
        User host = userRepository.save(user(54));
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());

        assertThatThrownBy(() -> sessionService.startVoting(created.session().getId(), host.getId()))
                .isInstanceOf(DomainRuleException.class)
                .hasMessage("session.not-enough-members");
    }

    @Test
    void startVoting_unknownSession_throwsSessionNotFound() {
        User someone = userRepository.save(user(55));

        assertThatThrownBy(() -> sessionService.startVoting(UUID.randomUUID(), someone.getId()))
                .isInstanceOf(SessionNotFoundException.class)
                .hasMessage("session.not-found");
    }

    @Test
    void cancelSession_hostInLobby_transitionsToCancelled() {
        User host = userRepository.save(user(60));
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());

        sessionService.cancelSession(created.session().getId(), host.getId());

        Session reloaded = sessionRepository.findById(created.session().getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(SessionState.CANCELLED);
    }

    @Test
    void cancelSession_publishesSessionCancelledEvent() {
        User host = userRepository.save(user(78));
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());
        List<SessionCancelledEvent> received = new ArrayList<>();
        ApplicationListener<ApplicationEvent> listener = event -> {
            if (event instanceof PayloadApplicationEvent<?> payloadEvent
                    && payloadEvent.getPayload() instanceof SessionCancelledEvent sessionCancelledEvent) {
                received.add(sessionCancelledEvent);
            }
        };
        applicationContext.addApplicationListener(listener);

        sessionService.cancelSession(created.session().getId(), host.getId());

        assertThat(received)
                .extracting(SessionCancelledEvent::sessionId)
                .containsExactly(created.session().getId());
    }

    @Test
    void cancelSession_nonHost_throwsForbidden() {
        User host = userRepository.save(user(61));
        User member = userRepository.save(user(62));
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());
        sessionService.join(created.session().getInviteCode(), member.getId());

        assertThatThrownBy(() -> sessionService.cancelSession(created.session().getId(), member.getId()))
                .isInstanceOf(SessionForbiddenException.class)
                .hasMessage("session.forbidden");
    }

    @Test
    void kick_hostRemovesMember_memberNoLongerInSession() {
        User host = userRepository.save(user(63));
        User member = userRepository.save(user(64));
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());
        sessionService.join(created.session().getInviteCode(), member.getId());

        sessionService.kick(created.session().getId(), host.getId(), member.getId());

        assertThat(isMemberInDb(created.session().getId(), member.getId())).isFalse();
        assertThat(memberCount(created.session().getId())).isEqualTo(1);
    }

    @Test
    void kick_nonHost_throwsForbidden() {
        User host = userRepository.save(user(65));
        User member = userRepository.save(user(66));
        User other = userRepository.save(user(67));
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());
        sessionService.join(created.session().getInviteCode(), member.getId());
        sessionService.join(created.session().getInviteCode(), other.getId());

        assertThatThrownBy(() -> sessionService.kick(created.session().getId(), member.getId(), other.getId()))
                .isInstanceOf(SessionForbiddenException.class)
                .hasMessage("session.forbidden");
    }

    @Test
    void updateParams_hostInLobby_updatesFields() {
        User host = userRepository.save(user(68));
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());
        UpdateSessionParamsRequest newParams = new UpdateSessionParamsRequest("New title", 30, 1440, true);

        sessionService.updateParams(created.session().getId(), host.getId(), newParams);

        Session reloaded = sessionRepository.findById(created.session().getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("New title");
        assertThat(reloaded.getDeckSize()).isEqualTo((short) 30);
        assertThat(reloaded.getVoteDeadlineMinutes()).isEqualTo(1440);
        assertThat(reloaded.isWatchlistOnly()).isTrue();
    }

    @Test
    void updateParams_nonHost_throwsForbidden() {
        User host = userRepository.save(user(69));
        User member = userRepository.save(user(70));
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());
        sessionService.join(created.session().getInviteCode(), member.getId());

        assertThatThrownBy(
                        () -> sessionService.updateParams(created.session().getId(), member.getId(), updateRequest()))
                .isInstanceOf(SessionForbiddenException.class)
                .hasMessage("session.forbidden");
    }

    @Test
    void updateParams_invalidDeckSize_throwsDomainRuleException() {
        User host = userRepository.save(user(71));
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());

        assertThatThrownBy(() -> sessionService.updateParams(
                        created.session().getId(),
                        host.getId(),
                        new UpdateSessionParamsRequest("Title", 15, 30, false)))
                .isInstanceOf(DomainRuleException.class)
                .hasMessage("validation.failed");
    }

    @Test
    void updateParams_partialUpdate_preservesOmittedFields() {
        User host = userRepository.save(user(107));
        SessionCreationResult created =
                sessionService.create(host.getId(), new CreateSessionRequest("Original", 10, 30, false));

        sessionService.updateParams(
                created.session().getId(), host.getId(), new UpdateSessionParamsRequest(null, 30, null, null));

        Session reloaded = sessionRepository.findById(created.session().getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("Original");
        assertThat(reloaded.getDeckSize()).isEqualTo((short) 30);
        assertThat(reloaded.getVoteDeadlineMinutes()).isEqualTo(30);
        assertThat(reloaded.isWatchlistOnly()).isFalse();
    }

    @Test
    void updateParams_sessionInVoting_throwsStateConflict() {
        User host = userRepository.save(user(72));
        User member = userRepository.save(user(73));
        insertMovie(700_010);
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());
        sessionService.join(created.session().getInviteCode(), member.getId());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Session session =
                    sessionRepository.findById(created.session().getId()).orElseThrow();
            session.startVoting(host.getId(), deckFor(700_010), "v1", clock);
            sessionRepository.saveAndFlush(session);
        });

        assertThatThrownBy(() -> sessionService.updateParams(created.session().getId(), host.getId(), updateRequest()))
                .isInstanceOf(SessionStateConflictException.class)
                .hasMessage("session.illegal-transition");
    }

    @Test
    void markWatched_nonHost_throwsForbidden() {
        User host = userRepository.save(user(74));
        User member = userRepository.save(user(75));
        insertMovie(700_011);
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());
        sessionService.join(created.session().getInviteCode(), member.getId());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Session session =
                    sessionRepository.findById(created.session().getId()).orElseThrow();
            session.startVoting(host.getId(), deckFor(700_011), "v1", clock);
            session.decide(700_011L, false, clock);
            sessionRepository.saveAndFlush(session);
        });

        assertThatThrownBy(() -> sessionService.markWatched(created.session().getId(), member.getId()))
                .isInstanceOf(SessionForbiddenException.class)
                .hasMessage("session.forbidden");
    }

    @Test
    void markWatched_afterDecision_removesWinnerFromMembersWatchlistsAndMarksSeen() {
        User host = userRepository.save(user(76));
        User member = userRepository.save(user(77));
        long winner = 700_012;
        insertMovie(winner);
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());
        sessionService.join(created.session().getInviteCode(), member.getId());
        addToWatchlist(host.getId(), winner);
        addToWatchlist(member.getId(), winner);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Session session =
                    sessionRepository.findById(created.session().getId()).orElseThrow();
            session.startVoting(host.getId(), deckFor(winner), "v1", clock);
            session.decide(winner, false, clock);
            sessionRepository.saveAndFlush(session);
        });

        sessionService.markWatched(created.session().getId(), host.getId());

        Session reloaded = sessionRepository.findById(created.session().getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(SessionState.WATCHED);
        assertThat(isInWatchlist(host.getId(), winner)).isFalse();
        assertThat(isInWatchlist(member.getId(), winner)).isFalse();
        assertThat(isSeen(host.getId(), winner)).isTrue();
        assertThat(isSeen(member.getId(), winner)).isTrue();
    }

    @Test
    void join_twoConcurrentJoinsAtCapacity_exactlyOneSucceeds() throws Exception {
        User host = userRepository.save(user(400));
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());
        String inviteCode = created.session().getInviteCode();
        for (int i = 0; i < 8; i++) {
            User member = userRepository.save(user(401 + i));
            sessionService.join(inviteCode, member.getId());
        }
        // host (1) + 8 joined = 9 members, exactly one seat left before the race (limit 10).

        User racer1 = userRepository.save(user(500));
        User racer2 = userRepository.save(user(501));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Exception> future1 = pool.submit(joinAttempt(ready, start, inviteCode, racer1.getId()));
            Future<Exception> future2 = pool.submit(joinAttempt(ready, start, inviteCode, racer2.getId()));

            ready.await();
            start.countDown();

            Exception result1 = future1.get(10, TimeUnit.SECONDS);
            Exception result2 = future2.get(10, TimeUnit.SECONDS);

            assertThat(memberCount(created.session().getId())).isEqualTo(10);

            long successes = Stream.of(result1, result2).filter(Objects::isNull).count();
            assertThat(successes).isEqualTo(1);

            Exception failure = result1 != null ? result1 : result2;
            assertThat(failure).isInstanceOf(DomainRuleException.class).hasMessage("session.full");
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void startVoting_nonMember_throwsSessionNotFound() {
        User host = userRepository.save(user(90));
        User stranger = userRepository.save(user(91));
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());

        assertThatThrownBy(() -> sessionService.startVoting(created.session().getId(), stranger.getId()))
                .isInstanceOf(SessionNotFoundException.class)
                .hasMessage("session.not-found");
    }

    @Test
    void cancelSession_nonMember_throwsSessionNotFound() {
        User host = userRepository.save(user(92));
        User stranger = userRepository.save(user(93));
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());

        assertThatThrownBy(() -> sessionService.cancelSession(created.session().getId(), stranger.getId()))
                .isInstanceOf(SessionNotFoundException.class)
                .hasMessage("session.not-found");
    }

    @Test
    void kick_nonMember_throwsSessionNotFound() {
        User host = userRepository.save(user(94));
        User member = userRepository.save(user(95));
        User stranger = userRepository.save(user(96));
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());
        sessionService.join(created.session().getInviteCode(), member.getId());

        assertThatThrownBy(() -> sessionService.kick(created.session().getId(), stranger.getId(), member.getId()))
                .isInstanceOf(SessionNotFoundException.class)
                .hasMessage("session.not-found");
    }

    @Test
    void updateParams_nonMember_throwsSessionNotFound() {
        User host = userRepository.save(user(97));
        User stranger = userRepository.save(user(98));
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());

        assertThatThrownBy(() -> sessionService.updateParams(
                        created.session().getId(),
                        stranger.getId(),
                        new UpdateSessionParamsRequest(null, null, null, null)))
                .isInstanceOf(SessionNotFoundException.class)
                .hasMessage("session.not-found");
    }

    @Test
    void markWatched_nonMember_throwsSessionNotFound() {
        User host = userRepository.save(user(99));
        User stranger = userRepository.save(user(100));
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());

        assertThatThrownBy(() -> sessionService.markWatched(created.session().getId(), stranger.getId()))
                .isInstanceOf(SessionNotFoundException.class)
                .hasMessage("session.not-found");
    }

    @Test
    void rate_nonMember_throwsSessionNotFound() {
        User host = userRepository.save(user(108));
        User stranger = userRepository.save(user(109));
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());

        assertThatThrownBy(() -> sessionService.rate(created.session().getId(), stranger.getId(), (short) 3))
                .isInstanceOf(SessionNotFoundException.class)
                .hasMessage("session.not-found");
    }

    @Test
    void cancelSession_alreadyCancelled_throwsStateConflict() {
        User host = userRepository.save(user(102));
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());
        sessionService.cancelSession(created.session().getId(), host.getId());

        assertThatThrownBy(() -> sessionService.cancelSession(created.session().getId(), host.getId()))
                .isInstanceOf(SessionStateConflictException.class)
                .hasMessage("session.illegal-transition");
    }

    @Test
    void join_returnsIdOfTheJoinedSession() {
        User host = userRepository.save(user(111));
        User joiner = userRepository.save(user(112));
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());

        UUID joinedSessionId = sessionService.join(created.session().getInviteCode(), joiner.getId());

        assertThat(joinedSessionId).isEqualTo(created.session().getId());
    }

    @Test
    void rate_memberAfterWatched_recordsRating() {
        User host = userRepository.save(user(103));
        User member = userRepository.save(user(104));
        insertMovie(700_021);
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());
        sessionService.join(created.session().getInviteCode(), member.getId());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Session session =
                    sessionRepository.findById(created.session().getId()).orElseThrow();
            session.startVoting(host.getId(), deckFor(700_021), "v1", clock);
            session.decide(700_021L, false, clock);
            session.markWatched(host.getId(), clock);
            sessionRepository.saveAndFlush(session);
        });

        sessionService.rate(created.session().getId(), member.getId(), (short) 5);

        assertThat(memberRating(created.session().getId(), member.getId())).isEqualTo(5);
    }

    @Test
    void rate_beforeWatched_throwsStateConflict() {
        User host = userRepository.save(user(113));
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());

        assertThatThrownBy(() -> sessionService.rate(created.session().getId(), host.getId(), (short) 5))
                .isInstanceOf(SessionStateConflictException.class)
                .hasMessage("session.illegal-transition");
    }

    @Test
    void rate_after49Hours_throwsDomainRuleException() {
        User host = userRepository.save(user(105));
        User member = userRepository.save(user(106));
        insertMovie(700_022);
        Instant watchedAt = Instant.parse("2026-01-01T00:00:00Z");
        Clock watchedClock = Clock.fixed(watchedAt, ZoneOffset.UTC);
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());
        sessionService.join(created.session().getInviteCode(), member.getId());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Session session =
                    sessionRepository.findById(created.session().getId()).orElseThrow();
            session.startVoting(host.getId(), deckFor(700_022), "v1", watchedClock);
            session.decide(700_022L, false, watchedClock);
            session.markWatched(host.getId(), watchedClock);
            sessionRepository.saveAndFlush(session);
        });

        Clock lateClock = Clock.fixed(watchedAt.plus(Duration.ofHours(49)), ZoneOffset.UTC);
        SessionService lateService = new SessionService(
                sessionRepository,
                new InviteCodeGenerator(),
                deckBuilder,
                eventPublisher,
                lateClock,
                5,
                "quorum_bot",
                "quorum-app",
                transactionManager);

        assertThatThrownBy(() -> lateService.rate(created.session().getId(), member.getId(), (short) 4))
                .isInstanceOf(DomainRuleException.class)
                .hasMessage("session.rating-window-closed");
    }

    @Test
    void startVoting_twoConcurrentStarts_exactlyOneSucceedsWithOneDeck() throws Exception {
        User host = userRepository.save(user(600));
        User member = userRepository.save(user(601));
        insertMovie(700_030);
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());
        sessionService.join(created.session().getInviteCode(), member.getId());
        // real matching needs a real candidate to build a non-empty deck from
        addToWatchlist(host.getId(), 700_030);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Exception> attempt = () -> {
            ready.countDown();
            start.await();
            try {
                sessionService.startVoting(created.session().getId(), host.getId());
                return null;
            } catch (Exception e) {
                return e;
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Exception> future1 = pool.submit(attempt);
            Future<Exception> future2 = pool.submit(attempt);

            ready.await();
            start.countDown();

            Exception result1 = future1.get(10, TimeUnit.SECONDS);
            Exception result2 = future2.get(10, TimeUnit.SECONDS);

            long successes = Stream.of(result1, result2).filter(Objects::isNull).count();
            assertThat(successes).isEqualTo(1);

            Exception failure = result1 != null ? result1 : result2;
            assertThat(failure)
                    .isInstanceOf(SessionStateConflictException.class)
                    .hasMessage("session.illegal-transition");

            assertThat(sessionRepository
                            .findById(created.session().getId())
                            .orElseThrow()
                            .getState())
                    .isEqualTo(SessionState.VOTING);
            // The loser's requireState(LOBBY) check throws before it ever reaches the deck-insert
            // loop, so there's no way for both racers' decks to land - just confirm one did.
            assertThat(deckItemCount(created.session().getId())).isGreaterThan(0);
        } finally {
            pool.shutdown();
        }
    }
}
