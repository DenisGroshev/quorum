package app.quorum.watchlist.domain;

import app.quorum.catalog.api.CatalogFacade;
import app.quorum.common.ContentLang;
import app.quorum.common.analytics.AnalyticsEvent;
import app.quorum.common.exception.DomainRuleException;
import app.quorum.common.exception.MovieNotFoundException;
import app.quorum.watchlist.api.MovieFlags;
import app.quorum.watchlist.api.WatchlistAgg;
import app.quorum.watchlist.api.WatchlistQueries;
import app.quorum.watchlist.infra.SeenItemRepository;
import app.quorum.watchlist.infra.WatchlistAggRepository;
import app.quorum.watchlist.infra.WatchlistBulkRepository;
import app.quorum.watchlist.infra.WatchlistItemRepository;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

@Service
public class WatchlistService implements WatchlistQueries {

    private final WatchlistItemRepository watchlistItemRepository;
    private final SeenItemRepository seenItemRepository;
    private final WatchlistAggRepository watchlistAggRepository;
    private final WatchlistBulkRepository watchlistBulkRepository;
    private final CatalogFacade catalogFacade;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final int watchlistMax;
    private final int seenMax;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public WatchlistService(
            WatchlistItemRepository watchlistItemRepository,
            SeenItemRepository seenItemRepository,
            WatchlistAggRepository watchlistAggRepository,
            WatchlistBulkRepository watchlistBulkRepository,
            CatalogFacade catalogFacade,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            @Value("${quorum.limits.watchlist-max}") int watchlistMax,
            @Value("${quorum.limits.seen-max}") int seenMax,
            PlatformTransactionManager transactionManager) {
        this.watchlistItemRepository = watchlistItemRepository;
        this.seenItemRepository = seenItemRepository;
        this.watchlistAggRepository = watchlistAggRepository;
        this.watchlistBulkRepository = watchlistBulkRepository;
        this.catalogFacade = catalogFacade;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.watchlistMax = watchlistMax;
        this.seenMax = seenMax;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void addToWatchlist(UUID userId, long tmdbId, ContentLang lang, String source) {
        ensureMovieExists(tmdbId, lang);

        UserMovieKey key = new UserMovieKey(userId, tmdbId);
        if (Boolean.TRUE.equals(transactionTemplate.execute(status -> watchlistItemRepository.existsById(key)))) {
            return;
        }

        try {
            requiresNewTransactionTemplate.executeWithoutResult(status -> {
                if (watchlistItemRepository.countByKeyUserId(userId) >= watchlistMax) {
                    throw new DomainRuleException("watchlist.limit-reached");
                }
                watchlistItemRepository.saveAndFlush(new WatchlistItem(userId, tmdbId, clock));
                Map<String, Object> props = new HashMap<>();
                props.put("tmdbId", tmdbId);
                props.put("source", source);
                eventPublisher.publishEvent(new AnalyticsEvent("watchlist_add", userId, props));
            });
        } catch (DataIntegrityViolationException raceLost) {
            boolean nowPresent = Boolean.TRUE.equals(
                    requiresNewTransactionTemplate.execute(status -> watchlistItemRepository.existsById(key)));
            if (!nowPresent) {
                throw raceLost;
            }
        }
    }

    public Page<WatchlistItem> getWatchlist(UUID userId, int page, int size) {
        return watchlistItemRepository.findByKeyUserIdOrderByAddedAtDesc(userId, PageRequest.of(page, size));
    }

    public Page<SeenItem> getSeenItems(UUID userId, int page, int size) {
        return seenItemRepository.findByKeyUserIdOrderByMarkedAtDesc(userId, PageRequest.of(page, size));
    }

    public void markSeen(UUID userId, long tmdbId, ContentLang lang) {
        ensureMovieExists(tmdbId, lang);

        UserMovieKey key = new UserMovieKey(userId, tmdbId);
        if (Boolean.TRUE.equals(transactionTemplate.execute(status -> seenItemRepository.existsById(key)))) {
            return;
        }

        try {
            requiresNewTransactionTemplate.executeWithoutResult(status -> {
                if (seenItemRepository.countByKeyUserId(userId) >= seenMax) {
                    throw new DomainRuleException("seen.limit-reached");
                }
                seenItemRepository.saveAndFlush(new SeenItem(userId, tmdbId, clock));
                removeFromWatchlist(userId, tmdbId);
            });
        } catch (DataIntegrityViolationException raceLost) {
            boolean nowPresent = Boolean.TRUE.equals(
                    requiresNewTransactionTemplate.execute(status -> seenItemRepository.existsById(key)));
            if (!nowPresent) {
                throw raceLost;
            }
        }
    }

    public MovieFlags getFlag(UUID userId, long tmdbId) {
        return getFlags(userId, List.of(tmdbId)).getOrDefault(tmdbId, new MovieFlags(false, false));
    }

    @Override
    public long watchlistCount(UUID userId) {
        return watchlistItemRepository.countByKeyUserId(userId);
    }

    public long seenCount(UUID userId) {
        return seenItemRepository.countByKeyUserId(userId);
    }

    public Map<Long, MovieFlags> getFlags(UUID userId, Collection<Long> tmdbIds) {
        if (tmdbIds.isEmpty()) {
            return Map.of();
        }

        Set<Long> inWatchlist = watchlistItemRepository.findByKeyUserIdAndKeyTmdbIdIn(userId, tmdbIds).stream()
                .map(item -> item.getKey().tmdbId())
                .collect(Collectors.toSet());
        Set<Long> seen = seenItemRepository.findByKeyUserIdAndKeyTmdbIdIn(userId, tmdbIds).stream()
                .map(item -> item.getKey().tmdbId())
                .collect(Collectors.toSet());

        return tmdbIds.stream()
                .distinct()
                .collect(Collectors.toMap(
                        tmdbId -> tmdbId,
                        tmdbId -> new MovieFlags(inWatchlist.contains(tmdbId), seen.contains(tmdbId))));
    }

    public Map<Long, WatchlistAgg> aggregateFor(Collection<UUID> memberIds) {
        return watchlistAggRepository.aggregateFor(memberIds).stream()
                .collect(Collectors.toMap(WatchlistAgg::tmdbId, agg -> agg));
    }

    @Override
    public void removeAndMarkSeen(Collection<UUID> userIds, long tmdbId) {
        transactionTemplate.executeWithoutResult(
                status -> watchlistBulkRepository.removeAndMarkSeen(userIds, tmdbId, clock.instant()));
    }

    public void unmarkSeen(UUID userId, long tmdbId) {
        UserMovieKey key = new UserMovieKey(userId, tmdbId);
        transactionTemplate.executeWithoutResult(status -> seenItemRepository.deleteByKey(key));
    }

    public void removeFromWatchlist(UUID userId, long tmdbId) {
        UserMovieKey key = new UserMovieKey(userId, tmdbId);
        transactionTemplate.executeWithoutResult(status -> watchlistItemRepository.deleteByKey(key));
    }

    private void ensureMovieExists(long tmdbId, ContentLang lang) {
        try {
            catalogFacade.getOrFetch(tmdbId, lang);
        } catch (HttpClientErrorException.NotFound notFound) {
            throw new MovieNotFoundException("movie.not-found");
        }
    }
}
