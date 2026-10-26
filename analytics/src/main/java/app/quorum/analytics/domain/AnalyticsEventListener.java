package app.quorum.analytics.domain;

import app.quorum.common.analytics.AnalyticsEvent;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AnalyticsEventListener {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsEventListener.class);

    private final AnalyticsEventRepository repository;
    private final Clock clock;

    public AnalyticsEventListener(AnalyticsEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAnalyticsEvent(AnalyticsEvent event) {
        try {
            repository.save(AnalyticsEventEntity.from(event, clock));
        } catch (Exception e) {
            log.warn("Failed to record analytics event of type {}", event.type(), e);
        }
    }
}
