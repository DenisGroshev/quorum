package app.quorum.notification.domain;

import app.quorum.notification.api.NotificationPublisher;
import app.quorum.notification.api.NotificationTemplate;
import app.quorum.session.api.NotificationSessionView;
import app.quorum.session.api.SessionGuard;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class VotingReminderJob {

    private static final Logger log = LoggerFactory.getLogger(VotingReminderJob.class);
    private static final Duration REMINDER_WINDOW = Duration.ofMinutes(30);
    static final int BATCH_SIZE = 50;

    private final SessionGuard sessionGuard;
    private final NotificationPublisher notificationPublisher;
    private final Clock clock;

    public VotingReminderJob(SessionGuard sessionGuard, NotificationPublisher notificationPublisher, Clock clock) {
        this.sessionGuard = sessionGuard;
        this.notificationPublisher = notificationPublisher;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1M")
    public void run() {
        Instant now = clock.instant();
        List<UUID> sessionIds = sessionGuard.votingSessionsNearingDeadline(now, now.plus(REMINDER_WINDOW), BATCH_SIZE);
        for (UUID sessionId : sessionIds) {
            try {
                remind(sessionId, now);
            } catch (Exception e) {
                log.error("Failed to send voting reminders for session {}, continuing with the rest", sessionId, e);
            }
        }
    }

    private void remind(UUID sessionId, Instant now) {
        NotificationSessionView view = sessionGuard.notificationView(sessionId);
        long minutesLeft =
                Math.max(0, Duration.between(now, view.votingDeadlineAt()).toMinutes());
        Map<String, String> params = Map.of(
                "sessionTitle", view.title(),
                "minutesLeft", String.valueOf(minutesLeft),
                "inviteCode", view.inviteCode());
        for (UUID memberId : sessionGuard.membersNotFinishedVoting(sessionId)) {
            notificationPublisher.publish(
                    "voting_reminder:" + sessionId + ":" + memberId,
                    memberId,
                    NotificationTemplate.VOTING_REMINDER,
                    params);
        }
    }
}
