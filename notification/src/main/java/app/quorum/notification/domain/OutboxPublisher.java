package app.quorum.notification.domain;

import app.quorum.notification.api.NotificationPublisher;
import app.quorum.notification.api.NotificationTemplate;
import app.quorum.notification.infra.OutboxRepository;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OutboxPublisher implements NotificationPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public OutboxPublisher(OutboxRepository outboxRepository, UserRepository userRepository, Clock clock) {
        this.outboxRepository = outboxRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Override
    public void publish(String eventKey, UUID recipient, NotificationTemplate template, Map<String, String> params) {
        try {
            User user = userRepository.findById(recipient).orElse(null);
            if (user == null || !user.isNotifyEnabled()) {
                return;
            }
            if (outboxRepository.existsByEventKey(eventKey)) {
                return;
            }
            outboxRepository.save(OutboxMessage.create(eventKey, recipient, template, params, clock));
        } catch (Exception e) {
            log.error("Failed to publish notification {} for recipient {}", eventKey, recipient, e);
        }
    }
}
