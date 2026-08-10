package app.quorum.watchlist.domain;

import app.quorum.session.api.SessionWatchedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class SessionWatchedEventListener {

    private final WatchlistService watchlistService;

    public SessionWatchedEventListener(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onSessionWatched(SessionWatchedEvent event) {
        watchlistService.removeAndMarkSeen(event.memberIds(), event.winnerTmdbId());
    }
}
