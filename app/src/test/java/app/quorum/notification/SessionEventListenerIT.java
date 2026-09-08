package app.quorum.notification;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.auth.api.TelegramProfile;
import app.quorum.notification.api.NotificationTemplate;
import app.quorum.notification.domain.OutboxMessage;
import app.quorum.notification.infra.OutboxRepository;
import app.quorum.session.domain.SessionCreationResult;
import app.quorum.session.domain.SessionService;
import app.quorum.session.web.dto.CreateSessionRequest;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import app.quorum.voting.domain.VoteValue;
import app.quorum.voting.domain.VotingService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
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
class SessionEventListenerIT {

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
    private VotingService votingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JdbcClient jdbcClient;

    private static User user(long tgUserId) {
        return User.registerFromTelegram(
                new TelegramProfile(tgUserId, "User " + tgUserId, "user" + tgUserId, null, "ru", true),
                Clock.systemUTC());
    }

    private static CreateSessionRequest defaultRequest() {
        return new CreateSessionRequest(null, null, null, false);
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

    private void addToWatchlist(UUID userId, long tmdbId) {
        jdbcClient
                .sql("INSERT INTO watchlist_items (user_id, tmdb_id, added_at) VALUES (:userId, :tmdbId, :addedAt)")
                .param("userId", userId)
                .param("tmdbId", tmdbId)
                .param("addedAt", Timestamp.from(Instant.now()))
                .update();
    }

    private List<OutboxMessage> outboxFor(NotificationTemplate template, Collection<UUID> candidateRecipients) {
        return outboxRepository.findAll().stream()
                .filter(m -> m.getTemplate() == template)
                .filter(m -> candidateRecipients.contains(m.getRecipientUserId()))
                .toList();
    }

    @Test
    void startVoting_fourMembers_createsThreeOutboxRowsExcludingHost() {
        User host = userRepository.save(user(1001));
        User member1 = userRepository.save(user(1002));
        User member2 = userRepository.save(user(1003));
        User member3 = userRepository.save(user(1004));
        insertMovie(900_001);
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());
        sessionService.join(created.session().getInviteCode(), member1.getId());
        sessionService.join(created.session().getInviteCode(), member2.getId());
        sessionService.join(created.session().getInviteCode(), member3.getId());
        addToWatchlist(host.getId(), 900_001);
        addToWatchlist(member1.getId(), 900_001);
        addToWatchlist(member2.getId(), 900_001);
        addToWatchlist(member3.getId(), 900_001);

        sessionService.startVoting(created.session().getId(), host.getId());

        List<OutboxMessage> outbox = outboxFor(
                NotificationTemplate.VOTING_STARTED,
                List.of(host.getId(), member1.getId(), member2.getId(), member3.getId()));
        assertThat(outbox)
                .extracting(OutboxMessage::getRecipientUserId)
                .containsExactlyInAnyOrder(member1.getId(), member2.getId(), member3.getId());
    }

    @Test
    void cancelSession_createsOutboxRowsForNonHostMembers() {
        User host = userRepository.save(user(1005));
        User member = userRepository.save(user(1006));
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());
        sessionService.join(created.session().getInviteCode(), member.getId());

        sessionService.cancelSession(created.session().getId(), host.getId());

        List<OutboxMessage> outbox =
                outboxFor(NotificationTemplate.SESSION_CANCELLED, List.of(host.getId(), member.getId()));
        assertThat(outbox).extracting(OutboxMessage::getRecipientUserId).containsExactly(member.getId());
    }

    @Test
    void finishVoting_decidesWinner_createsOutboxRowsForAllMembersIncludingHost() {
        User host = userRepository.save(user(1007));
        User member = userRepository.save(user(1008));
        insertMovie(900_002);
        SessionCreationResult created = sessionService.create(host.getId(), defaultRequest());
        sessionService.join(created.session().getInviteCode(), member.getId());
        addToWatchlist(host.getId(), 900_002);
        addToWatchlist(member.getId(), 900_002);
        sessionService.startVoting(created.session().getId(), host.getId());
        votingService.castVote(created.session().getId(), host.getId(), 900_002, VoteValue.LIKE);

        votingService.finishVoting(created.session().getId());

        List<OutboxMessage> outbox =
                outboxFor(NotificationTemplate.WINNER_DECIDED, List.of(host.getId(), member.getId()));
        assertThat(outbox)
                .extracting(OutboxMessage::getRecipientUserId)
                .containsExactlyInAnyOrder(host.getId(), member.getId());
    }
}
