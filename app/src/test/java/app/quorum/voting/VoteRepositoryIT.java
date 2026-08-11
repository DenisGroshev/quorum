package app.quorum.voting;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.auth.api.TelegramProfile;
import app.quorum.session.domain.DeckResult;
import app.quorum.session.domain.ScoredMovie;
import app.quorum.session.domain.Session;
import app.quorum.session.domain.SessionParams;
import app.quorum.session.infra.SessionRepository;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import app.quorum.voting.domain.Vote;
import app.quorum.voting.domain.VoteKey;
import app.quorum.voting.domain.VoteValue;
import app.quorum.voting.infra.VoteRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
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
class VoteRepositoryIT {

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
    private VoteRepository voteRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private Clock clock;

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

    /** A VOTING-state session with the given movies actually in its deck (votes FK to deck_items). */
    private Session votingSession(User host, User member, String code, long... tmdbIds) {
        for (long id : tmdbIds) {
            insertMovie(id);
        }
        Session session = Session.create(host.getId(), params(), code, clock);
        session.join(member.getId(), clock);
        session.startVoting(host.getId(), deckOf(tmdbIds), "v1", clock);
        return sessionRepository.saveAndFlush(session);
    }

    @Test
    void save_andFindById_roundTrips() {
        User host = userRepository.save(user(1));
        User member = userRepository.save(user(2));
        Session session = votingSession(host, member, "VOTE0001", 601L);
        VoteKey key = new VoteKey(session.getId(), host.getId(), 601L);

        voteRepository.save(new Vote(key, VoteValue.LIKE, clock));

        Vote loaded = voteRepository.findById(key).orElseThrow();
        assertThat(loaded.getValue()).isEqualTo(VoteValue.LIKE);
    }

    @Test
    void save_secondVoteForSameKey_overwritesRatherThanDuplicates() {
        User host = userRepository.save(user(3));
        User member = userRepository.save(user(4));
        Session session = votingSession(host, member, "VOTE0002", 602L);
        VoteKey key = new VoteKey(session.getId(), host.getId(), 602L);
        voteRepository.saveAndFlush(new Vote(key, VoteValue.DISLIKE, clock));

        voteRepository.saveAndFlush(new Vote(key, VoteValue.LIKE, clock));

        assertThat(voteRepository.findById(key).orElseThrow().getValue()).isEqualTo(VoteValue.LIKE);
        assertThat(voteRepository.count()).isEqualTo(1);
    }

    @Test
    void countByMemberInSession_countsOnlyThatMembersVotesInThatSession() {
        User host = userRepository.save(user(5));
        User member = userRepository.save(user(6));
        Session sessionA = votingSession(host, member, "VOTE0003", 603L, 604L);
        Session sessionB = votingSession(host, member, "VOTE0004", 605L);
        voteRepository.saveAndFlush(new Vote(new VoteKey(sessionA.getId(), host.getId(), 603L), VoteValue.LIKE, clock));
        voteRepository.saveAndFlush(
                new Vote(new VoteKey(sessionA.getId(), host.getId(), 604L), VoteValue.DISLIKE, clock));
        voteRepository.saveAndFlush(
                new Vote(new VoteKey(sessionA.getId(), member.getId(), 603L), VoteValue.LIKE, clock));
        voteRepository.saveAndFlush(new Vote(new VoteKey(sessionB.getId(), host.getId(), 605L), VoteValue.LIKE, clock));

        assertThat(voteRepository.countByKey_SessionIdAndKey_UserId(sessionA.getId(), host.getId()))
                .isEqualTo(2);
        assertThat(voteRepository.countByKey_SessionIdAndKey_UserId(sessionA.getId(), member.getId()))
                .isEqualTo(1);
        assertThat(voteRepository.countByKey_SessionIdAndKey_UserId(sessionB.getId(), host.getId()))
                .isEqualTo(1);
    }

    @Test
    void existsSuperlike_trueOnlyForThatMemberInThatSession() {
        User host = userRepository.save(user(7));
        User member = userRepository.save(user(8));
        Session sessionA = votingSession(host, member, "VOTE0005", 606L);
        Session sessionB = votingSession(host, member, "VOTE0006", 607L);
        voteRepository.saveAndFlush(
                new Vote(new VoteKey(sessionA.getId(), host.getId(), 606L), VoteValue.SUPERLIKE, clock));

        assertThat(voteRepository.existsByKey_SessionIdAndKey_UserIdAndValue(
                        sessionA.getId(), host.getId(), VoteValue.SUPERLIKE))
                .isTrue();
        assertThat(voteRepository.existsByKey_SessionIdAndKey_UserIdAndValue(
                        sessionA.getId(), member.getId(), VoteValue.SUPERLIKE))
                .isFalse();
        assertThat(voteRepository.existsByKey_SessionIdAndKey_UserIdAndValue(
                        sessionB.getId(), host.getId(), VoteValue.SUPERLIKE))
                .isFalse();
    }

    @Test
    void existsSuperlike_falseForLikeAndDislikeVotes() {
        User host = userRepository.save(user(9));
        User member = userRepository.save(user(10));
        Session session = votingSession(host, member, "VOTE0007", 608L, 609L);
        voteRepository.saveAndFlush(new Vote(new VoteKey(session.getId(), host.getId(), 608L), VoteValue.LIKE, clock));
        voteRepository.saveAndFlush(
                new Vote(new VoteKey(session.getId(), host.getId(), 609L), VoteValue.DISLIKE, clock));

        assertThat(voteRepository.existsByKey_SessionIdAndKey_UserIdAndValue(
                        session.getId(), host.getId(), VoteValue.SUPERLIKE))
                .isFalse();
    }

    @Test
    void countDistinctVoters_countsUniqueMembersNotVoteRows() {
        User host = userRepository.save(user(11));
        User member = userRepository.save(user(12));
        Session session = votingSession(host, member, "VOTE0008", 610L, 611L);
        // host votes on BOTH movies - must still count as ONE voter, not two vote rows
        voteRepository.saveAndFlush(new Vote(new VoteKey(session.getId(), host.getId(), 610L), VoteValue.LIKE, clock));
        voteRepository.saveAndFlush(
                new Vote(new VoteKey(session.getId(), host.getId(), 611L), VoteValue.DISLIKE, clock));

        assertThat(voteRepository.countDistinctVotersBySessionId(session.getId()))
                .isEqualTo(1);
    }

    @Test
    void countDistinctVoters_doesNotLeakAcrossSessions() {
        User host = userRepository.save(user(13));
        User member = userRepository.save(user(14));
        Session sessionA = votingSession(host, member, "VOTE0009", 612L);
        Session sessionB = votingSession(host, member, "VOTE0010", 613L);
        voteRepository.saveAndFlush(new Vote(new VoteKey(sessionA.getId(), host.getId(), 612L), VoteValue.LIKE, clock));
        voteRepository.saveAndFlush(
                new Vote(new VoteKey(sessionA.getId(), member.getId(), 612L), VoteValue.LIKE, clock));
        voteRepository.saveAndFlush(new Vote(new VoteKey(sessionB.getId(), host.getId(), 613L), VoteValue.LIKE, clock));

        assertThat(voteRepository.countDistinctVotersBySessionId(sessionA.getId()))
                .isEqualTo(2);
        assertThat(voteRepository.countDistinctVotersBySessionId(sessionB.getId()))
                .isEqualTo(1);
    }

    @Test
    void countDistinctVoters_noVotesYet_isZero() {
        User host = userRepository.save(user(15));
        User member = userRepository.save(user(16));
        Session session = votingSession(host, member, "VOTE0011", 614L);

        assertThat(voteRepository.countDistinctVotersBySessionId(session.getId()))
                .isEqualTo(0);
    }
}
