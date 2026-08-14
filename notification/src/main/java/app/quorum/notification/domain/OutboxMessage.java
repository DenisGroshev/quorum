package app.quorum.notification.domain;

import app.quorum.notification.api.NotificationTemplate;
import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "notification_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxMessage {

    @Id
    private UUID id;

    @Column(name = "event_key", nullable = false, unique = true, length = 128)
    private String eventKey;

    @Column(name = "recipient_user_id", nullable = false)
    private UUID recipientUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private NotificationTemplate template;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, String> params;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    public static OutboxMessage create(
            String eventKey,
            UUID recipientUserId,
            NotificationTemplate template,
            Map<String, String> params,
            Clock clock) {
        OutboxMessage m = new OutboxMessage();
        m.id = UuidCreator.getTimeOrderedEpoch();
        m.eventKey = eventKey;
        m.recipientUserId = recipientUserId;
        m.template = template;
        m.params = params;
        m.status = OutboxStatus.PENDING;
        m.attempts = 0;
        m.createdAt = clock.instant();
        m.nextAttemptAt = clock.instant();
        return m;
    }

    public void markSent(Clock c) {
        this.status = OutboxStatus.SENT;
        this.sentAt = c.instant();
    }

    public void scheduleRetry(Clock c) {
        this.attempts++;
        if (attempts >= 3) {
            this.status = OutboxStatus.FAILED;
            return;
        }
        this.nextAttemptAt = c.instant().plus(Duration.ofSeconds((long) Math.pow(4, attempts)));
    }

    public void scheduleRetry(Clock c, Duration explicitDelay) {
        this.attempts++;
        if (attempts >= 3) {
            this.status = OutboxStatus.FAILED;
            return;
        }
        this.nextAttemptAt = c.instant().plus(explicitDelay);
    }

    public void skip() {
        this.status = OutboxStatus.SKIPPED;
    }
}
