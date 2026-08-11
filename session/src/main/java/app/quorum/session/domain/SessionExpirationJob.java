package app.quorum.session.domain;

import app.quorum.common.SessionState;
import app.quorum.session.infra.SessionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class SessionExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(SessionExpirationJob.class);
    static final int BATCH_SIZE = 50;
    private static final Duration LOBBY_TTL = Duration.ofDays(7);
    private static final Duration DECIDED_TTL = Duration.ofDays(7);

    private final SessionRepository sessionRepository;
    private final Clock clock;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public SessionExpirationJob(
            SessionRepository sessionRepository, Clock clock, PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.clock = clock;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "PT1M")
    public void run() {
        expireStaleLobbies();
        archiveOldDecisions();
    }

    private void expireStaleLobbies() {
        Instant cutoff = clock.instant().minus(LOBBY_TTL);
        List<Session> batch = sessionRepository.findByStateAndCreatedAtBefore(
                SessionState.LOBBY, cutoff, PageRequest.of(0, BATCH_SIZE));
        for (Session found : batch) {
            processOne(found.getId(), Session::expire);
        }
    }

    private void archiveOldDecisions() {
        Instant cutoff = clock.instant().minus(DECIDED_TTL);
        List<Session> batch = sessionRepository.findByStateAndDecidedAtBefore(
                SessionState.DECIDED, cutoff, PageRequest.of(0, BATCH_SIZE));
        for (Session found : batch) {
            processOne(found.getId(), Session::archive);
        }
    }

    private void processOne(UUID sessionId, Consumer<Session> transition) {
        try {
            requiresNewTransactionTemplate.executeWithoutResult(status -> {
                Session session = sessionRepository.lockById(sessionId).orElseThrow();
                transition.accept(session);
                sessionRepository.saveAndFlush(session);
            });
        } catch (Exception e) {
            log.error("Failed to process scheduled transition for session {}, continuing with the rest", sessionId, e);
        }
    }
}
