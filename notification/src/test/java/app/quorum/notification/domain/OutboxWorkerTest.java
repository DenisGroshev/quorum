package app.quorum.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.quorum.auth.api.TelegramProfile;
import app.quorum.notification.api.NotificationTemplate;
import app.quorum.notification.infra.OutboxRepository;
import app.quorum.telegram.client.TelegramBotClient;
import app.quorum.telegram.client.TelegramSendResult;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class OutboxWorkerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private MessageRenderer messageRenderer;

    @Mock
    private TelegramBotClient telegramBotClient;

    @Mock
    private PlatformTransactionManager transactionManager;

    private OutboxWorker worker;

    @BeforeEach
    void setUp() {
        worker = new OutboxWorker(
                outboxRepository, userRepository, messageRenderer, telegramBotClient, CLOCK, transactionManager, 20);
    }

    private static User user(long tgUserId) {
        return User.registerFromTelegram(
                new TelegramProfile(tgUserId, "User " + tgUserId, "user" + tgUserId, null, "ru"), CLOCK);
    }

    private static OutboxMessage message(UUID recipientId) {
        return OutboxMessage.create(
                "evt-" + recipientId, recipientId, NotificationTemplate.VOTING_STARTED, Map.of(), CLOCK);
    }

    private void stubReady(OutboxMessage... messages) {
        when(outboxRepository.findReadyToSend(eq(NOW), any())).thenReturn(List.of(messages));
        for (OutboxMessage message : messages) {
            when(outboxRepository.findLockedById(message.getId())).thenReturn(Optional.of(message));
        }
    }

    @Test
    void run_messageSentSuccessfully_marksStatusSent() {
        User recipient = user(1);
        OutboxMessage msg = message(recipient.getId());
        stubReady(msg);
        when(userRepository.findById(recipient.getId())).thenReturn(Optional.of(recipient));
        when(messageRenderer.render(any(), any())).thenReturn(new RenderedMessage("text", "btn", "url"));
        when(telegramBotClient.sendMessage(anyLong(), any(), any(), any())).thenReturn(TelegramSendResult.success());

        worker.run();

        assertThat(msg.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(msg.getSentAt()).isNotNull();
        verify(outboxRepository).save(msg);
    }

    @Test
    void run_transientFailure_schedulesRetryWithIncrementedAttempts() {
        User recipient = user(2);
        OutboxMessage msg = message(recipient.getId());
        stubReady(msg);
        when(userRepository.findById(recipient.getId())).thenReturn(Optional.of(recipient));
        when(messageRenderer.render(any(), any())).thenReturn(new RenderedMessage("text", "btn", "url"));
        when(telegramBotClient.sendMessage(anyLong(), any(), any(), any()))
                .thenReturn(TelegramSendResult.transientFailure("server error"));

        worker.run();

        assertThat(msg.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(msg.getAttempts()).isEqualTo(1);
        assertThat(msg.getNextAttemptAt()).isAfter(NOW);
    }

    @Test
    void run_transientFailureWithRetryAfter_usesExplicitDelayInsteadOfBackoff() {
        User recipient = user(3);
        OutboxMessage msg = message(recipient.getId());
        stubReady(msg);
        when(userRepository.findById(recipient.getId())).thenReturn(Optional.of(recipient));
        when(messageRenderer.render(any(), any())).thenReturn(new RenderedMessage("text", "btn", "url"));
        when(telegramBotClient.sendMessage(anyLong(), any(), any(), any()))
                .thenReturn(TelegramSendResult.transientFailure("too many requests", 5));

        worker.run();

        assertThat(msg.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    void run_thirdTransientFailure_marksFailed() {
        User recipient = user(4);
        OutboxMessage msg = message(recipient.getId());
        msg.scheduleRetry(CLOCK);
        msg.scheduleRetry(CLOCK);
        stubReady(msg);
        when(userRepository.findById(recipient.getId())).thenReturn(Optional.of(recipient));
        when(messageRenderer.render(any(), any())).thenReturn(new RenderedMessage("text", "btn", "url"));
        when(telegramBotClient.sendMessage(anyLong(), any(), any(), any()))
                .thenReturn(TelegramSendResult.transientFailure("server error"));

        worker.run();

        assertThat(msg.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(msg.getAttempts()).isEqualTo(3);
    }

    @Test
    void run_permanentFailure_marksSkippedAndDisablesRecipientNotifications() {
        User recipient = user(5);
        OutboxMessage msg = message(recipient.getId());
        stubReady(msg);
        when(userRepository.findById(recipient.getId())).thenReturn(Optional.of(recipient));
        when(messageRenderer.render(any(), any())).thenReturn(new RenderedMessage("text", "btn", "url"));
        when(telegramBotClient.sendMessage(anyLong(), any(), any(), any()))
                .thenReturn(TelegramSendResult.permanentFailure("bot was blocked by the user"));

        worker.run();

        assertThat(msg.getStatus()).isEqualTo(OutboxStatus.SKIPPED);
        assertThat(recipient.isNotifyEnabled()).isFalse();
        verify(userRepository).save(recipient);
    }

    @Test
    void run_oneMessageThrows_othersInBatchStillProcessed() {
        User recipient1 = user(6);
        User recipient2 = user(7);
        OutboxMessage msg1 = message(recipient1.getId());
        OutboxMessage msg2 = message(recipient2.getId());
        when(outboxRepository.findReadyToSend(eq(NOW), any())).thenReturn(List.of(msg1, msg2));
        when(outboxRepository.findLockedById(msg1.getId())).thenThrow(new RuntimeException("boom"));
        when(outboxRepository.findLockedById(msg2.getId())).thenReturn(Optional.of(msg2));
        when(userRepository.findById(recipient2.getId())).thenReturn(Optional.of(recipient2));
        when(messageRenderer.render(any(), any())).thenReturn(new RenderedMessage("text", "btn", "url"));
        when(telegramBotClient.sendMessage(anyLong(), any(), any(), any())).thenReturn(TelegramSendResult.success());

        worker.run();

        assertThat(msg2.getStatus()).isEqualTo(OutboxStatus.SENT);
        verify(outboxRepository).save(msg2);
    }

    @Test
    void run_emptyQueue_doesNothing() {
        when(outboxRepository.findReadyToSend(eq(NOW), any())).thenReturn(List.of());

        worker.run();

        verify(outboxRepository, never()).save(any());
    }
}
