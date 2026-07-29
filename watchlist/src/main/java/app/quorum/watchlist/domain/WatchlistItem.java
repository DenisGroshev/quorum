package app.quorum.watchlist.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "watchlist_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WatchlistItem {

    @EmbeddedId
    private UserMovieKey key;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    public WatchlistItem(UUID userId, Long tmdbId, Clock clock) {
        this.key = new UserMovieKey(userId, tmdbId);
        this.addedAt = clock.instant();
    }
}
