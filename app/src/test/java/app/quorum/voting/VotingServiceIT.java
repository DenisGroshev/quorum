package app.quorum.voting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.quorum.auth.api.TelegramProfile;
import app.quorum.common.SessionState;
import app.quorum.common.exception.DomainRuleException;
import app.quorum.common.exception.SessionForbiddenException;
import app.quorum.common.exception.SessionStateConflictException;
import app.quorum.session.api.VotingFinishedEvent;
import app.quorum.session.domain.DeckResult;
import app.quorum.session.domain.ScoredMovie;
import app.quorum.session.domain.Session;
import app.quorum.session.domain.SessionParams;
import app.quorum.session.infra.SessionRepository;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import app.quorum.voting.domain.VoteValue;
import app.quorum.voting.domain.VotingService;
import app.quorum.voting.infra.VoteRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class VotingServiceIT {

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
    private VotingService votingService;

    @Autowired
    private VoteRepository voteRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private Clock clock;

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

    private static User user(long tgUserId) {
        return User.registerFromTelegram(
                new TelegramProfile(tgUserId, "User " + tgUserId, "user" + tgUserId, null, "ru"), Clock.systemUTC());
    }

    private static SessionParams params() {
        return new SessionParams("Movie night", 10, 30, false);
    }

    private static DeckResult deckOf(long... tmdbIds) {
        List<ScoredMovie> movies = java.util.Arrays.stream(tmdbIds)
                .mapToObj(id -> new ScoredMovie(id, new BigDecimal("9.0"), List.of()))
                .toList();
        return new DeckResult(movies, "v1");
    }

    private Session votingSession(User host, List<User> otherMembers, String code, long... tmdbIds) {
        for (long id : tmdbIds) {
            insertMovie(id);
        }
        Session session = Session.create(host.getId(), params(), code, clock);
        for (User member : otherMembers) {
            session.join(member.getId(), clock);
        }
        session.startVoting(host.getId(), deckOf(tmdbIds), "v1", clock);
        return sessionRepository.saveAndFlush(session);
    }

    private Instant completedVotingAt(UUID sessionId, UUID userId) {
        return jdbcClient
                .sql("SELECT completed_voting_at FROM session_members WHERE session_id = :sid AND user_id = :uid")
                .param("sid", sessionId)
                .param("uid", userId)
                .query(Instant.class)
                .optional()
                .orElse(null);
    }

    private long analyticsEventCount(String type) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM analytics_events WHERE type = :type")
                .param("type", type)
                .query(Long.class)
                .single();
    }

    private String analyticsEventProps(String type, UUID userId) {
        return jdbcClient
                .sql("SELECT props::text FROM analytics_events WHERE type = :type AND user_id = :userId")
                .param("type", type)
                .param("userId", userId)
                .query(String.class)
                .single();
    }

    private String latestAnalyticsEventProps(String type) {
        return jdbcClient
                .sql("SELECT props::text FROM analytics_events WHERE type = :type ORDER BY occurred_at DESC LIMIT 1")
                .param("type", type)
                .query(String.class)
                .single();
    }

    @Test
    void castVote_newVote_persists() {
        User host = userRepository.save(user(201));
        User a = userRepository.save(user(202));
        Session session = votingSession(host, List.of(a), "SVC0001", 801L);

        votingService.castVote(session.getId(), host.getId(), 801L, VoteValue.LIKE);

        var key = new app.quorum.voting.domain.VoteKey(session.getId(), host.getId(), 801L);
        assertThat(voteRepository.findById(key)).isPresent();
        assertThat(voteRepository.findById(key).get().getValue()).isEqualTo(VoteValue.LIKE);
    }

    @Test
    void castVote_secondCallSameMovie_overwritesRatherThanDuplicates() {
        User host = userRepository.save(user(203));
        User a = userRepository.save(user(204));
        Session session = votingSession(host, List.of(a), "SVC0002", 802L);

        votingService.castVote(session.getId(), host.getId(), 802L, VoteValue.LIKE);
        votingService.castVote(session.getId(), host.getId(), 802L, VoteValue.DISLIKE);

        var key = new app.quorum.voting.domain.VoteKey(session.getId(), host.getId(), 802L);
        assertThat(voteRepository.findById(key).orElseThrow().getValue()).isEqualTo(VoteValue.DISLIKE);
        assertThat(voteRepository.countByKey_SessionIdAndKey_UserId(session.getId(), host.getId()))
                .isEqualTo(1);
    }

    @Test
    void castVote_secondSuperlikeOnDifferentMovie_throws422() {
        User host = userRepository.save(user(205));
        User a = userRepository.save(user(206));
        Session session = votingSession(host, List.of(a), "SVC0003", 803L, 804L);
        votingService.castVote(session.getId(), host.getId(), 803L, VoteValue.SUPERLIKE);

        assertThatThrownBy(() -> votingService.castVote(session.getId(), host.getId(), 804L, VoteValue.SUPERLIKE))
                .isInstanceOf(DomainRuleException.class)
                .hasMessage("vote.superlike-exhausted");
    }

    @Test
    void castVote_resubmitSuperlikeOnSameMovie_isIdempotentNoThrow() {
        User host = userRepository.save(user(207));
        User a = userRepository.save(user(208));
        Session session = votingSession(host, List.of(a), "SVC0004", 805L);
        votingService.castVote(session.getId(), host.getId(), 805L, VoteValue.SUPERLIKE);

        votingService.castVote(session.getId(), host.getId(), 805L, VoteValue.SUPERLIKE);

        var key = new app.quorum.voting.domain.VoteKey(session.getId(), host.getId(), 805L);
        assertThat(voteRepository.findById(key).orElseThrow().getValue()).isEqualTo(VoteValue.SUPERLIKE);
    }

    @Test
    void castVote_nonParticipant_throws403() {
        User host = userRepository.save(user(209));
        User a = userRepository.save(user(210));
        User stranger = userRepository.save(user(211));
        Session session = votingSession(host, List.of(a), "SVC0005", 806L);

        assertThatThrownBy(() -> votingService.castVote(session.getId(), stranger.getId(), 806L, VoteValue.LIKE))
                .isInstanceOf(SessionForbiddenException.class);
    }

    @Test
    void castVote_sessionAlreadyDecided_throws409() {
        User host = userRepository.save(user(212));
        User a = userRepository.save(user(213));
        Session session = votingSession(host, List.of(a), "SVC0006", 807L);
        session.decide(807L, false, clock);
        sessionRepository.saveAndFlush(session);

        assertThatThrownBy(() -> votingService.castVote(session.getId(), host.getId(), 807L, VoteValue.LIKE))
                .isInstanceOf(SessionStateConflictException.class);
    }

    @Test
    void castVote_movieNotInDeck_throws422NotSql500() {
        User host = userRepository.save(user(214));
        User a = userRepository.save(user(215));
        Session session = votingSession(host, List.of(a), "SVC0007", 808L);
        insertMovie(809L); // exists as a movie, but never added to this session's deck

        assertThatThrownBy(() -> votingService.castVote(session.getId(), host.getId(), 809L, VoteValue.LIKE))
                .isInstanceOf(DomainRuleException.class)
                .hasMessage("vote.movie-not-in-deck");
    }

    @Test
    void castVote_lastVoteForMember_marksCompletedVotingAt() {
        User host = userRepository.save(user(216));
        User a = userRepository.save(user(217));
        Session session = votingSession(host, List.of(a), "SVC0008", 810L, 811L);

        votingService.castVote(session.getId(), host.getId(), 810L, VoteValue.LIKE);
        assertThat(completedVotingAt(session.getId(), host.getId())).isNull();

        votingService.castVote(session.getId(), host.getId(), 811L, VoteValue.LIKE);
        assertThat(completedVotingAt(session.getId(), host.getId())).isNotNull();
    }

    @Test
    void castVote_partialVotes_completedVotingAtStaysNullForOtherMembers() {
        User host = userRepository.save(user(218));
        User a = userRepository.save(user(219));
        Session session = votingSession(host, List.of(a), "SVC0009", 812L, 813L);

        votingService.castVote(session.getId(), host.getId(), 812L, VoteValue.LIKE);
        votingService.castVote(session.getId(), host.getId(), 813L, VoteValue.LIKE);

        assertThat(completedVotingAt(session.getId(), host.getId())).isNotNull();
        assertThat(completedVotingAt(session.getId(), a.getId())).isNull();
    }

    @Test
    void castVote_allMembersFinish_everyMemberMarkedComplete() {
        User host = userRepository.save(user(220));
        User a = userRepository.save(user(221));
        Session session = votingSession(host, List.of(a), "SVC0010", 814L);

        votingService.castVote(session.getId(), host.getId(), 814L, VoteValue.LIKE);
        votingService.castVote(session.getId(), a.getId(), 814L, VoteValue.DISLIKE);

        assertThat(completedVotingAt(session.getId(), host.getId())).isNotNull();
        assertThat(completedVotingAt(session.getId(), a.getId())).isNotNull();
    }

    @Test
    void finishVoting_decidesWinnerFromVotes() {
        User host = userRepository.save(user(222));
        User a = userRepository.save(user(223));
        Session session = votingSession(host, List.of(a), "SVC0011", 815L, 816L);
        votingService.castVote(session.getId(), host.getId(), 815L, VoteValue.LIKE);
        votingService.castVote(session.getId(), a.getId(), 815L, VoteValue.LIKE);
        votingService.castVote(session.getId(), host.getId(), 816L, VoteValue.LIKE);

        votingService.finishVoting(session.getId());

        Session reloaded = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(SessionState.DECIDED);
        assertThat(reloaded.getWinnerTmdbId()).isEqualTo(815L);
        assertThat(reloaded.isWinnerContested()).isFalse();
    }

    @Test
    void finishVoting_noVotesAtAll_transitionsToExpired() {
        User host = userRepository.save(user(224));
        User a = userRepository.save(user(225));
        Session session = votingSession(host, List.of(a), "SVC0012", 817L);

        votingService.finishVoting(session.getId());

        Session reloaded = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(SessionState.EXPIRED);
        assertThat(reloaded.getWinnerTmdbId()).isNull();
    }

    @Test
    void finishVoting_calledAgainAfterAlreadyDecided_quietlyNoOps() {
        User host = userRepository.save(user(226));
        User a = userRepository.save(user(227));
        Session session = votingSession(host, List.of(a), "SVC0013", 818L);
        votingService.castVote(session.getId(), host.getId(), 818L, VoteValue.LIKE);
        votingService.finishVoting(session.getId());

        votingService.finishVoting(session.getId());

        Session reloaded = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(SessionState.DECIDED);
        assertThat(reloaded.getWinnerTmdbId()).isEqualTo(818L);
    }

    @Test
    void finishVoting_sessionNotInVoting_quietlyNoOps() {
        User host = userRepository.save(user(228));
        Session session = Session.create(host.getId(), params(), "SVC0014", clock);
        sessionRepository.saveAndFlush(session);

        votingService.finishVoting(session.getId());

        Session reloaded = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(SessionState.LOBBY);
    }

    @Test
    void finishVoting_allVetoed_decidesCompromiseWinner() {
        User host = userRepository.save(user(229));
        User a = userRepository.save(user(230));
        Session session = votingSession(host, List.of(a), "SVC0015", 819L);
        votingService.castVote(session.getId(), host.getId(), 819L, VoteValue.DISLIKE);
        votingService.castVote(session.getId(), a.getId(), 819L, VoteValue.DISLIKE);

        Session reloaded = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(SessionState.DECIDED);
        assertThat(reloaded.getWinnerTmdbId()).isEqualTo(819L);
        assertThat(reloaded.isWinnerContested()).isTrue();
    }

    @Test
    void castVote_lastVoteAutomaticallyTriggersFinishVoting() {
        User host = userRepository.save(user(231));
        User a = userRepository.save(user(232));
        Session session = votingSession(host, List.of(a), "SVC0016", 820L);

        votingService.castVote(session.getId(), host.getId(), 820L, VoteValue.LIKE);
        Session midway = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(midway.getState()).isEqualTo(SessionState.VOTING);

        votingService.castVote(session.getId(), a.getId(), 820L, VoteValue.LIKE);

        Session reloaded = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(SessionState.DECIDED);
        assertThat(reloaded.getWinnerTmdbId()).isEqualTo(820L);
    }

    @Test
    void finishVoting_concurrentCalls_produceExactlyOneWinner() throws Exception {
        User host = userRepository.save(user(233));
        User a = userRepository.save(user(234));
        Session session = votingSession(host, List.of(a), "SVC0017", 821L, 822L);
        votingService.castVote(session.getId(), host.getId(), 821L, VoteValue.SUPERLIKE);
        votingService.castVote(session.getId(), host.getId(), 822L, VoteValue.LIKE);
        // 'a' never votes, so castVote's own auto-finish trigger never fires and the session is
        // still sitting in VOTING when the race below starts — otherwise the "last vote" path
        // would finish it before either racing thread gets a turn, and there'd be nothing to race.
        Session stillVoting = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(stillVoting.getState()).isEqualTo(SessionState.VOTING);

        // The winner is a deterministic function of the same votes, so both threads would agree
        // on it even without a lock — that alone can't prove the lock does anything. What the
        // lock actually prevents is the transition (and its notification) firing more than once,
        // so that's the thing this test has to observe: how many times did concludeVoting really
        // flip the state, not just what it flipped it to.
        AtomicInteger finishedEvents = new AtomicInteger();
        ApplicationListener<ApplicationEvent> listener = event -> {
            if (event instanceof PayloadApplicationEvent<?> payloadEvent
                    && payloadEvent.getPayload() instanceof VotingFinishedEvent) {
                finishedEvents.incrementAndGet();
            }
        };
        applicationContext.addApplicationListener(listener);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Callable<Void> task = () -> {
                ready.countDown();
                go.await();
                votingService.finishVoting(session.getId());
                return null;
            };

            Future<Void> first = executor.submit(task);
            Future<Void> second = executor.submit(task);
            ready.await();
            go.countDown();

            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }

        Session reloaded = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(SessionState.DECIDED);
        assertThat(reloaded.getWinnerTmdbId()).isEqualTo(821L);
        assertThat(finishedEvents.get()).isEqualTo(1);
    }

    @Test
    void castVote_demoteSuperlikeThenSuperlikeAnotherMovie_succeeds() {
        User host = userRepository.save(user(235));
        User a = userRepository.save(user(236));
        Session session = votingSession(host, List.of(a), "SVC0018", 823L, 824L);
        votingService.castVote(session.getId(), host.getId(), 823L, VoteValue.SUPERLIKE);

        votingService.castVote(session.getId(), host.getId(), 823L, VoteValue.LIKE);
        votingService.castVote(session.getId(), host.getId(), 824L, VoteValue.SUPERLIKE);

        var oldKey = new app.quorum.voting.domain.VoteKey(session.getId(), host.getId(), 823L);
        var newKey = new app.quorum.voting.domain.VoteKey(session.getId(), host.getId(), 824L);
        assertThat(voteRepository.findById(oldKey).orElseThrow().getValue()).isEqualTo(VoteValue.LIKE);
        assertThat(voteRepository.findById(newKey).orElseThrow().getValue()).isEqualTo(VoteValue.SUPERLIKE);
    }

    @Test
    void finishVoting_vetoDenominatorIsVotersNotTotalMembers_endToEnd() {
        User host = userRepository.save(user(237));
        User b = userRepository.save(user(238));
        User c = userRepository.save(user(239));
        User d = userRepository.save(user(240));
        User e = userRepository.save(user(241));
        Session session = votingSession(host, List.of(b, c, d, e), "SVC0019", 825L);
        // 5 total members, only 2 ever vote, both DISLIKE the only candidate.
        // votersCount = 2 -> 2/2 = 100% -> vetoed -> falls back to compromise.
        // totalMembers = 5 -> 2/5 = 40% -> would NOT be vetoed if that denominator were used by mistake.
        votingService.castVote(session.getId(), host.getId(), 825L, VoteValue.DISLIKE);
        votingService.castVote(session.getId(), b.getId(), 825L, VoteValue.DISLIKE);

        votingService.finishVoting(session.getId());

        Session reloaded = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(SessionState.DECIDED);
        assertThat(reloaded.getWinnerTmdbId()).isEqualTo(825L);
        assertThat(reloaded.isWinnerContested())
                .as("with the correct voters-only denominator, 2/2 dislikes should trigger veto and force "
                        + "the compromise flag - if this is false, the wiring is using total member count instead")
                .isTrue();
    }

    @Test
    void castVote_recordsVoteCastAnalyticsEventWithVoteType() {
        User host = userRepository.save(user(242));
        User a = userRepository.save(user(243));
        Session session = votingSession(host, List.of(a), "SVC0020", 826L);

        votingService.castVote(session.getId(), host.getId(), 826L, VoteValue.SUPERLIKE);

        assertThat(analyticsEventProps("vote_cast", host.getId())).contains("\"voteType\": \"SUPERLIKE\"");
    }

    @Test
    void finishVoting_decided_recordsSessionDecidedAnalyticsEventWithContestedAndVotersOnlyCount() {
        User host = userRepository.save(user(244));
        User a = userRepository.save(user(245));
        User b = userRepository.save(user(246));
        Session session = votingSession(host, List.of(a, b), "SVC0021", 827L);
        votingService.castVote(session.getId(), host.getId(), 827L, VoteValue.LIKE);
        votingService.castVote(session.getId(), a.getId(), 827L, VoteValue.LIKE);

        votingService.finishVoting(session.getId());

        String props = latestAnalyticsEventProps("session_decided");
        assertThat(props).contains("\"contested\": false");
        assertThat(props)
                .as("votedCount must reflect only members who actually voted (2), not total members (3)")
                .contains("\"votedCount\": 2");
    }

    @Test
    void finishVoting_noVotesAtAll_doesNotRecordSessionDecidedEvent() {
        User host = userRepository.save(user(247));
        User a = userRepository.save(user(248));
        Session session = votingSession(host, List.of(a), "SVC0022", 828L);
        long before = analyticsEventCount("session_decided");

        votingService.finishVoting(session.getId());

        assertThat(analyticsEventCount("session_decided")).isEqualTo(before);
    }
}
