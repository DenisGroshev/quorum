package app.quorum.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.quorum.auth.api.TelegramProfile;
import app.quorum.notification.api.NotificationTemplate;
import app.quorum.notification.infra.OutboxRepository;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private UserRepository userRepository;

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxPublisher(outboxRepository, userRepository, CLOCK);
    }

    private static User user(long tgUserId, boolean notifyEnabled) {
        User user = User.registerFromTelegram(
                new TelegramProfile(tgUserId, "User " + tgUserId, "user" + tgUserId, null, "ru"), CLOCK);
        user.updateSettings(notifyEnabled, null);
        return user;
    }

    @Test
    void publish_newEventKey_savesPendingMessage() {
        UUID recipient = UUID.randomUUID();
        when(userRepository.findById(recipient)).thenReturn(Optional.of(user(1, true)));
        when(outboxRepository.existsByEventKey("evt-1")).thenReturn(false);

        publisher.publish("evt-1", recipient, NotificationTemplate.VOTING_STARTED, Map.of("sessionTitle", "Movie"));

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getEventKey()).isEqualTo("evt-1");
        assertThat(captor.getValue().getRecipientUserId()).isEqualTo(recipient);
        assertThat(captor.getValue().getTemplate()).isEqualTo(NotificationTemplate.VOTING_STARTED);
        assertThat(captor.getValue().getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    void publish_duplicateEventKey_doesNotSaveAgain() {
        UUID recipient = UUID.randomUUID();
        when(userRepository.findById(recipient)).thenReturn(Optional.of(user(2, true)));
        when(outboxRepository.existsByEventKey("evt-2")).thenReturn(true);

        publisher.publish("evt-2", recipient, NotificationTemplate.VOTING_STARTED, Map.of());

        verify(outboxRepository, never()).save(any());
    }

    @Test
    void publish_notifyDisabled_doesNotCreateMessage() {
        UUID recipient = UUID.randomUUID();
        when(userRepository.findById(recipient)).thenReturn(Optional.of(user(3, false)));

        publisher.publish("evt-3", recipient, NotificationTemplate.VOTING_STARTED, Map.of());

        verify(outboxRepository, never()).save(any());
    }

    @Test
    void publish_unknownRecipient_doesNotCreateMessage() {
        UUID recipient = UUID.randomUUID();
        when(userRepository.findById(recipient)).thenReturn(Optional.empty());

        publisher.publish("evt-5", recipient, NotificationTemplate.VOTING_STARTED, Map.of());

        verify(outboxRepository, never()).save(any());
    }

    @Test
    void publish_repositoryThrows_exceptionDoesNotPropagate() {
        UUID recipient = UUID.randomUUID();
        when(userRepository.findById(recipient)).thenReturn(Optional.of(user(4, true)));
        when(outboxRepository.existsByEventKey("evt-4")).thenReturn(false);
        when(outboxRepository.save(any())).thenThrow(new RuntimeException("boom"));

        publisher.publish("evt-4", recipient, NotificationTemplate.VOTING_STARTED, Map.of());
    }
}
