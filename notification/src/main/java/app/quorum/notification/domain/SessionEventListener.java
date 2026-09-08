package app.quorum.notification.domain;

import app.quorum.catalog.api.CatalogFacade;
import app.quorum.catalog.api.MovieDto;
import app.quorum.common.ContentLang;
import app.quorum.notification.api.NotificationPublisher;
import app.quorum.notification.api.NotificationTemplate;
import app.quorum.session.api.NotificationSessionView;
import app.quorum.session.api.SessionCancelledEvent;
import app.quorum.session.api.SessionGuard;
import app.quorum.session.api.VotingFinishedEvent;
import app.quorum.session.api.VotingStartedEvent;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class SessionEventListener {

    private static final ContentLang MOVIE_LOOKUP_LANG = ContentLang.RU;

    private final SessionGuard sessionGuard;
    private final NotificationPublisher notificationPublisher;
    private final UserRepository userRepository;
    private final CatalogFacade catalogFacade;

    public SessionEventListener(
            SessionGuard sessionGuard,
            NotificationPublisher notificationPublisher,
            UserRepository userRepository,
            CatalogFacade catalogFacade) {
        this.sessionGuard = sessionGuard;
        this.notificationPublisher = notificationPublisher;
        this.userRepository = userRepository;
        this.catalogFacade = catalogFacade;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onVotingStarted(VotingStartedEvent event) {
        NotificationSessionView view = sessionGuard.notificationView(event.sessionId());
        Map<String, String> params = Map.of(
                "hostName",
                hostDisplayName(view.hostId()),
                "sessionTitle",
                view.title(),
                "inviteCode",
                view.inviteCode());
        for (UUID memberId : view.memberIds()) {
            if (memberId.equals(view.hostId())) {
                continue;
            }
            notificationPublisher.publish(
                    "voting_started:" + event.sessionId() + ":" + memberId,
                    memberId,
                    NotificationTemplate.VOTING_STARTED,
                    params);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onSessionCancelled(SessionCancelledEvent event) {
        NotificationSessionView view = sessionGuard.notificationView(event.sessionId());
        Map<String, String> params = Map.of(
                "hostName",
                hostDisplayName(view.hostId()),
                "sessionTitle",
                view.title(),
                "inviteCode",
                view.inviteCode());
        for (UUID memberId : view.memberIds()) {
            if (memberId.equals(view.hostId())) {
                continue;
            }
            notificationPublisher.publish(
                    "session_cancelled:" + event.sessionId() + ":" + memberId,
                    memberId,
                    NotificationTemplate.SESSION_CANCELLED,
                    params);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onVotingFinished(VotingFinishedEvent event) {
        NotificationSessionView view = sessionGuard.notificationView(event.sessionId());
        if (view.winnerTmdbId() == null) {
            return;
        }
        Map<String, String> params =
                Map.of("movieTitle", movieTitle(view.winnerTmdbId()), "inviteCode", view.inviteCode());
        for (UUID memberId : view.memberIds()) {
            notificationPublisher.publish(
                    "winner_decided:" + event.sessionId() + ":" + memberId,
                    memberId,
                    NotificationTemplate.WINNER_DECIDED,
                    params);
        }
    }

    private String hostDisplayName(UUID hostId) {
        return userRepository.findById(hostId).map(User::getDisplayName).orElse("");
    }

    private String movieTitle(long tmdbId) {
        MovieDto movie =
                catalogFacade.getCached(List.of(tmdbId), MOVIE_LOOKUP_LANG).get(tmdbId);
        if (movie == null) {
            return "";
        }
        return movie.title() != null ? movie.title() : movie.originalTitle();
    }
}
