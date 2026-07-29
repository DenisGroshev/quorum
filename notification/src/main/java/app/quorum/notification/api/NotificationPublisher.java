package app.quorum.notification.api;

import java.util.Map;
import java.util.UUID;

public interface NotificationPublisher {
    void publish(String eventKey, UUID recipient, NotificationTemplate template, Map<String, String> params);
}
