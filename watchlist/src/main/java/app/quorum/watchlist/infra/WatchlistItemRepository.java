package app.quorum.watchlist.infra;

import app.quorum.watchlist.domain.UserMovieKey;
import app.quorum.watchlist.domain.WatchlistItem;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchlistItemRepository extends JpaRepository<WatchlistItem, UserMovieKey> {

    Page<WatchlistItem> findByKeyUserIdOrderByAddedAtDesc(UUID userId, Pageable pageable);

    long countByKeyUserId(UUID userId);

    List<WatchlistItem> findByKeyUserIdAndKeyTmdbIdIn(UUID userId, Collection<Long> tmdbIds);

    void deleteByKey(UserMovieKey key);
}
