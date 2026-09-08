package app.quorum.notification;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.auth.api.TelegramProfile;
import app.quorum.notification.api.NotificationTemplate;
import app.quorum.notification.domain.OutboxMessage;
import app.quorum.notification.infra.OutboxRepository;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutboxRepositoryIT {

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
    private OutboxRepository outboxRepository;

    @Autowired
    private UserRepository userRepository;

    private static User user(long tgUserId) {
        return User.registerFromTelegram(
                new TelegramProfile(tgUserId, "User " + tgUserId, "user" + tgUserId, null, "ru"), Clock.systemUTC());
    }

    private static OutboxMessage message(String eventKey, UUID recipientId, Instant createdAndDueAt) {
        return OutboxMessage.create(
                eventKey,
                recipientId,
                NotificationTemplate.VOTING_STARTED,
                Map.of(),
                Clock.fixed(createdAndDueAt, ZoneOffset.UTC));
    }

    @Test
    @Transactional
    void findReadyToSend_returnsPendingMessageDueNow() {
        User recipient = userRepository.save(user(101));
        Instant due = Instant.parse("2026-01-01T10:00:00Z");
        OutboxMessage ready = message("evt-ready-1", recipient.getId(), due);
        outboxRepository.saveAndFlush(ready);

        List<OutboxMessage> found = outboxRepository.findReadyToSend(due, PageRequest.of(0, 50));

        assertThat(found).extracting(OutboxMessage::getId).containsExactly(ready.getId());
    }

    @Test
    @Transactional
    void findReadyToSend_excludesMessageWithFutureNextAttempt() {
        User recipient = userRepository.save(user(102));
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        Instant notDueYet = now.plusSeconds(60);
        outboxRepository.saveAndFlush(message("evt-future-1", recipient.getId(), notDueYet));

        List<OutboxMessage> found = outboxRepository.findReadyToSend(now, PageRequest.of(0, 50));

        assertThat(found).isEmpty();
    }

    @Test
    @Transactional
    void findReadyToSend_excludesNonPendingStatuses() {
        User recipient = userRepository.save(user(103));
        Instant due = Instant.parse("2026-01-01T10:00:00Z");
        Clock clock = Clock.fixed(due, ZoneOffset.UTC);

        OutboxMessage sent = message("evt-sent-1", recipient.getId(), due);
        sent.markSent(clock);
        OutboxMessage skipped = message("evt-skipped-1", recipient.getId(), due);
        skipped.skip();
        OutboxMessage pending = message("evt-pending-1", recipient.getId(), due);
        outboxRepository.saveAllAndFlush(List.of(sent, skipped, pending));

        List<OutboxMessage> found = outboxRepository.findReadyToSend(due, PageRequest.of(0, 50));

        assertThat(found).extracting(OutboxMessage::getId).containsExactly(pending.getId());
    }

    @Test
    @Transactional
    void findReadyToSend_ordersByCreatedAtAscending() {
        User recipient = userRepository.save(user(104));
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        OutboxMessage newest = message("evt-order-3", recipient.getId(), now.minusSeconds(10));
        OutboxMessage oldest = message("evt-order-1", recipient.getId(), now.minusSeconds(30));
        OutboxMessage middle = message("evt-order-2", recipient.getId(), now.minusSeconds(20));
        // saved out of chronological order to prove the ordering isn't an accident of insert order
        outboxRepository.saveAllAndFlush(List.of(newest, oldest, middle));

        List<OutboxMessage> found = outboxRepository.findReadyToSend(now, PageRequest.of(0, 50));

        assertThat(found)
                .extracting(OutboxMessage::getId)
                .containsExactly(oldest.getId(), middle.getId(), newest.getId());
    }

    @Test
    @Transactional
    void findReadyToSend_limitsBatchSize() {
        User recipient = userRepository.save(user(105));
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        OutboxMessage first = message("evt-batch-1", recipient.getId(), now.minusSeconds(30));
        OutboxMessage second = message("evt-batch-2", recipient.getId(), now.minusSeconds(20));
        OutboxMessage third = message("evt-batch-3", recipient.getId(), now.minusSeconds(10));
        outboxRepository.saveAllAndFlush(List.of(first, second, third));

        List<OutboxMessage> found = outboxRepository.findReadyToSend(now, PageRequest.of(0, 2));

        assertThat(found).extracting(OutboxMessage::getId).containsExactly(first.getId(), second.getId());
    }
}
