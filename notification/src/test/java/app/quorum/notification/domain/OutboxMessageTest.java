package app.quorum.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.notification.api.NotificationTemplate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxMessageTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static OutboxMessage message() {
        return OutboxMessage.create("evt-1", UUID.randomUUID(), NotificationTemplate.VOTING_STARTED, Map.of(), CLOCK);
    }

    @Test
    void scheduleRetry_withExplicitDelay_usesGivenDelayInsteadOfBackoff() {
        OutboxMessage message = message();

        message.scheduleRetry(CLOCK, Duration.ofSeconds(5));

        assertThat(message.getAttempts()).isEqualTo(1);
        assertThat(message.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(message.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    void scheduleRetry_withExplicitDelay_thirdAttempt_marksFailed() {
        OutboxMessage message = message();

        message.scheduleRetry(CLOCK, Duration.ofSeconds(5));
        message.scheduleRetry(CLOCK, Duration.ofSeconds(5));
        message.scheduleRetry(CLOCK, Duration.ofSeconds(5));

        assertThat(message.getAttempts()).isEqualTo(3);
        assertThat(message.getStatus()).isEqualTo(OutboxStatus.FAILED);
    }
}
