package app.quorum.voting.domain;

import app.quorum.session.api.SessionGuard;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class VotingDeadlineJob {

    private static final Logger log = LoggerFactory.getLogger(VotingDeadlineJob.class);
    static final int BATCH_SIZE = 50;

    private final SessionGuard sessionGuard;
    private final VotingService votingService;
    private final Clock clock;

    public VotingDeadlineJob(SessionGuard sessionGuard, VotingService votingService, Clock clock) {
        this.sessionGuard = sessionGuard;
        this.votingService = votingService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1M")
    public void run() {
        List<UUID> overdue = sessionGuard.votingSessionsPastDeadline(clock.instant(), BATCH_SIZE);
        for (UUID sessionId : overdue) {
            try {
                votingService.finishVoting(sessionId);
            } catch (Exception e) {
                log.error("Failed to conclude overdue voting for session {}, continuing with the rest", sessionId, e);
            }
        }
    }
}
