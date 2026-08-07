package app.quorum.watchlist.infra;

import app.quorum.watchlist.domain.SeenItem;
import app.quorum.watchlist.domain.UserMovieKey;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeenItemRepository extends JpaRepository<SeenItem, UserMovieKey> {

    Page<SeenItem> findByKeyUserIdOrderByMarkedAtDesc(UUID userId, Pageable pageable);

    long countByKeyUserId(UUID userId);

    List<SeenItem> findByKeyUserIdAndKeyTmdbIdIn(UUID userId, Collection<Long> tmdbIds);

    void deleteByKey(UserMovieKey key);
}
