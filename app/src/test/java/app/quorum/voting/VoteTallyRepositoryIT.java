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
import app.quorum.voting.domain.VoteTally;
import app.quorum.voting.domain.VoteValue;
import app.quorum.voting.infra.VoteRepository;
import app.quorum.voting.infra.VoteTallyRepository;
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
class VoteTallyRepositoryIT {

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
    private VoteTallyRepository voteTallyRepository;

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

    private void vote(Session session, User voter, long tmdbId, VoteValue value) {
        voteRepository.saveAndFlush(new Vote(new VoteKey(session.getId(), voter.getId(), tmdbId), value, clock));
    }

    private static VoteTally tallyOf(List<VoteTally> tallies, long tmdbId) {
        return tallies.stream()
                .filter(t -> t.tmdbId() == tmdbId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no tally for tmdbId=" + tmdbId));
    }

    @Test
    void tallyFor_aggregatesPointsSuperlikesDislikesPerMovie() {
        User host = userRepository.save(user(101));
        User a = userRepository.save(user(102));
        User b = userRepository.save(user(103));
        Session session = votingSession(host, List.of(a, b), "TALLY001", 701L, 702L);

        // movie 701: LIKE + LIKE + SUPERLIKE = 1 + 1 + 2 = 4 points, 1 superlike, 0 dislikes
        vote(session, host, 701L, VoteValue.LIKE);
        vote(session, a, 701L, VoteValue.LIKE);
        vote(session, b, 701L, VoteValue.SUPERLIKE);
        // movie 702: DISLIKE only = 0 points, 0 superlikes, 1 dislike
        vote(session, host, 702L, VoteValue.DISLIKE);

        List<VoteTally> tallies = voteTallyRepository.tallyFor(session.getId());

        VoteTally movie701 = tallyOf(tallies, 701L);
        assertThat(movie701.points()).isEqualTo(4);
        assertThat(movie701.superlikes()).isEqualTo(1);
        assertThat(movie701.dislikes()).isEqualTo(0);

        VoteTally movie702 = tallyOf(tallies, 702L);
        assertThat(movie702.points()).isEqualTo(0);
        assertThat(movie702.superlikes()).isEqualTo(0);
        assertThat(movie702.dislikes()).isEqualTo(1);
    }

    @Test
    void tallyFor_doesNotLeakAcrossSessions() {
        User host = userRepository.save(user(104));
        User a = userRepository.save(user(105));
        Session sessionA = votingSession(host, List.of(a), "TALLY002", 703L);
        Session sessionB = votingSession(host, List.of(a), "TALLY003", 704L);
        vote(sessionA, host, 703L, VoteValue.LIKE);
        vote(sessionB, host, 704L, VoteValue.SUPERLIKE);

        List<VoteTally> talliesA = voteTallyRepository.tallyFor(sessionA.getId());

        assertThat(talliesA).hasSize(1);
        assertThat(talliesA.get(0).tmdbId()).isEqualTo(703L);
        assertThat(talliesA.get(0).points()).isEqualTo(1);
    }

    @Test
    void tallyFor_movieWithNoVotesYet_isAbsentFromResult() {
        User host = userRepository.save(user(106));
        User a = userRepository.save(user(107));
        Session session = votingSession(host, List.of(a), "TALLY004", 705L, 706L);
        vote(session, host, 705L, VoteValue.LIKE);
        // 706L is in the deck but nobody voted on it

        List<VoteTally> tallies = voteTallyRepository.tallyFor(session.getId());

        assertThat(tallies).hasSize(1);
        assertThat(tallies.get(0).tmdbId()).isEqualTo(705L);
    }

    @Test
    void tallyFor_noVotesAtAll_returnsEmptyList() {
        User host = userRepository.save(user(108));
        User a = userRepository.save(user(109));
        Session session = votingSession(host, List.of(a), "TALLY005", 707L);

        List<VoteTally> tallies = voteTallyRepository.tallyFor(session.getId());

        assertThat(tallies).isEmpty();
    }
}
