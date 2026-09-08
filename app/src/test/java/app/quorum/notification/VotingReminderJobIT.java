package app.quorum.notification;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.auth.api.TelegramProfile;
import app.quorum.notification.api.NotificationTemplate;
import app.quorum.notification.domain.OutboxMessage;
import app.quorum.notification.domain.VotingReminderJob;
import app.quorum.notification.infra.OutboxRepository;
import app.quorum.session.domain.SessionCreationResult;
import app.quorum.session.domain.SessionService;
import app.quorum.session.web.dto.CreateSessionRequest;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
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
class VotingReminderJobIT {

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
    private VotingReminderJob votingReminderJob;

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

    private static CreateSessionRequest requestWithDeadline(int voteDeadlineMinutes) {
        return new CreateSessionRequest(null, null, voteDeadlineMinutes, false);
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

    private List<OutboxMessage> reminderOutboxFor(User host, User member) {
        return outboxRepository.findAll().stream()
                .filter(m -> m.getTemplate() == NotificationTemplate.VOTING_REMINDER)
                .filter(m -> m.getRecipientUserId().equals(host.getId())
                        || m.getRecipientUserId().equals(member.getId()))
                .toList();
    }

    @Test
    void run_sessionNearingDeadline_remindsMembersWhoHaveNotFinishedVoting() {
        User host = userRepository.save(user(2001));
        User member = userRepository.save(user(2002));
        insertMovie(900_101);
        SessionCreationResult created = sessionService.create(host.getId(), requestWithDeadline(30));
        sessionService.join(created.session().getInviteCode(), member.getId());
        sessionService.startVoting(created.session().getId(), host.getId());

        votingReminderJob.run();

        assertThat(reminderOutboxFor(host, member))
                .extracting(OutboxMessage::getRecipientUserId)
                .containsExactlyInAnyOrder(host.getId(), member.getId());
    }

    @Test
    void run_calledTwice_secondCallCreatesNoDuplicates() {
        User host = userRepository.save(user(2003));
        User member = userRepository.save(user(2004));
        insertMovie(900_102);
        SessionCreationResult created = sessionService.create(host.getId(), requestWithDeadline(30));
        sessionService.join(created.session().getInviteCode(), member.getId());
        sessionService.startVoting(created.session().getId(), host.getId());

        votingReminderJob.run();
        votingReminderJob.run();

        assertThat(reminderOutboxFor(host, member))
                .extracting(OutboxMessage::getRecipientUserId)
                .containsExactlyInAnyOrder(host.getId(), member.getId());
    }
}
