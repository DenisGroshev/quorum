package app.quorum.analytics.domain;

import app.quorum.common.analytics.AnalyticsEvent;
import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "analytics_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalyticsEventEntity {

    @Id
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, length = 64)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> props;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public static AnalyticsEventEntity from(AnalyticsEvent event, Clock clock) {
        AnalyticsEventEntity entity = new AnalyticsEventEntity();
        entity.id = UuidCreator.getTimeOrderedEpoch();
        entity.userId = event.userId();
        entity.type = event.type();
        entity.props = event.props();
        entity.occurredAt = clock.instant();
        return entity;
    }
}
