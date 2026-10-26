package app.quorum.common.analytics;

import java.util.Map;
import java.util.UUID;

public record AnalyticsEvent(String type, UUID userId, Map<String, Object> props) {

    public AnalyticsEvent(String type, UUID userId) {
        this(type, userId, Map.of());
    }
}
