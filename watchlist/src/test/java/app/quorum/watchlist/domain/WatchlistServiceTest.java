package app.quorum.watchlist.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import app.quorum.catalog.api.CatalogFacade;
import app.quorum.catalog.api.MovieDto;
import app.quorum.common.ContentLang;
import app.quorum.common.exception.DomainRuleException;
import app.quorum.common.exception.MovieNotFoundException;
import app.quorum.watchlist.api.MovieFlags;
import app.quorum.watchlist.api.WatchlistAgg;
import app.quorum.watchlist.infra.SeenItemRepository;
import app.quorum.watchlist.infra.WatchlistAggRepository;
import app.quorum.watchlist.infra.WatchlistBulkRepository;
import app.quorum.watchlist.infra.WatchlistItemRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.client.HttpClientErrorException;

@ExtendWith(MockitoExtension.class)
class WatchlistServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);
    private static final int WATCHLIST_MAX = 3;
    private static final int SEEN_MAX = 5;
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final long TMDB_ID = 603L;
    private static final UserMovieKey KEY = new UserMovieKey(USER_ID, TMDB_ID);

    @Mock
    private WatchlistItemRepository watchlistItemRepository;

    @Mock
    private CatalogFacade catalogFacade;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    @Mock
    private SeenItemRepository seenItemRepository;

    @Mock
    private WatchlistAggRepository watchlistAggRepository;

    @Mock
    private WatchlistBulkRepository watchlistBulkRepository;

    private WatchlistService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        service = new WatchlistService(
                watchlistItemRepository,
                seenItemRepository,
                watchlistAggRepository,
                watchlistBulkRepository,
                catalogFacade,
                CLOCK,
                WATCHLIST_MAX,
                SEEN_MAX,
                transactionManager);
    }

    private static MovieDto movieDto(long tmdbId) {
        return new MovieDto(
                tmdbId, "Title", "Original Title", 1999, 120, null, Set.of(), null, BigDecimal.ZERO, 0, "en", null);
    }

    @Test
    void addToWatchlist_notYetInList_fetchesMovieFirstThenSaves() {
        when(catalogFacade.getOrFetch(TMDB_ID, ContentLang.RU)).thenReturn(movieDto(TMDB_ID));
        when(watchlistItemRepository.existsById(KEY)).thenReturn(false);
        when(watchlistItemRepository.countByKeyUserId(USER_ID)).thenReturn(0L);

        service.addToWatchlist(USER_ID, TMDB_ID, ContentLang.RU);

        InOrder order = inOrder(catalogFacade, watchlistItemRepository);
        order.verify(catalogFacade).getOrFetch(TMDB_ID, ContentLang.RU);
        ArgumentCaptor<WatchlistItem> captor = ArgumentCaptor.forClass(WatchlistItem.class);
        order.verify(watchlistItemRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getKey()).isEqualTo(KEY);
        assertThat(captor.getValue().getAddedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void addToWatchlist_alreadyInList_returnsWithoutInsertingOrCheckingLimit() {
        when(catalogFacade.getOrFetch(TMDB_ID, ContentLang.RU)).thenReturn(movieDto(TMDB_ID));
        when(watchlistItemRepository.existsById(KEY)).thenReturn(true);

        assertDoesNotThrow(() -> service.addToWatchlist(USER_ID, TMDB_ID, ContentLang.RU));

        verify(watchlistItemRepository, never()).saveAndFlush(any());
        verify(watchlistItemRepository, never()).countByKeyUserId(any());
    }

    @Test
    void addToWatchlist_limitReached_throwsDomainRuleException_andDoesNotInsert() {
        when(catalogFacade.getOrFetch(TMDB_ID, ContentLang.RU)).thenReturn(movieDto(TMDB_ID));
        when(watchlistItemRepository.existsById(KEY)).thenReturn(false);
        when(watchlistItemRepository.countByKeyUserId(USER_ID)).thenReturn((long) WATCHLIST_MAX);

        assertThrows(DomainRuleException.class, () -> service.addToWatchlist(USER_ID, TMDB_ID, ContentLang.RU));

        verify(watchlistItemRepository, never()).saveAndFlush(any());
    }

    @Test
    void addToWatchlist_movieMissingEverywhere_throwsMovieNotFound_beforeTouchingWatchlist() {
        when(catalogFacade.getOrFetch(TMDB_ID, ContentLang.RU))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null));

        assertThrows(MovieNotFoundException.class, () -> service.addToWatchlist(USER_ID, TMDB_ID, ContentLang.RU));

        verifyNoInteractions(watchlistItemRepository);
    }

    @Test
    void addToWatchlist_concurrentInsertWinsRace_convergesWithoutThrowing() {
        when(catalogFacade.getOrFetch(TMDB_ID, ContentLang.RU)).thenReturn(movieDto(TMDB_ID));
        when(watchlistItemRepository.existsById(KEY)).thenReturn(false, true);
        when(watchlistItemRepository.countByKeyUserId(USER_ID)).thenReturn(0L);
        when(watchlistItemRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup key"));

        assertDoesNotThrow(() -> service.addToWatchlist(USER_ID, TMDB_ID, ContentLang.RU));

        verify(watchlistItemRepository, times(2)).existsById(KEY);
    }

    @Test
    void addToWatchlist_insertFailsForUnrelatedReason_rethrowsOriginalException() {
        when(catalogFacade.getOrFetch(TMDB_ID, ContentLang.RU)).thenReturn(movieDto(TMDB_ID));
        when(watchlistItemRepository.existsById(KEY)).thenReturn(false);
        when(watchlistItemRepository.countByKeyUserId(USER_ID)).thenReturn(0L);
        DataIntegrityViolationException failure = new DataIntegrityViolationException("fk violation");
        when(watchlistItemRepository.saveAndFlush(any())).thenThrow(failure);

        DataIntegrityViolationException thrown = assertThrows(
                DataIntegrityViolationException.class, () -> service.addToWatchlist(USER_ID, TMDB_ID, ContentLang.RU));
        assertThat(thrown).isSameAs(failure);
    }

    @Test
    void removeFromWatchlist_alwaysDelegatesToDeleteByKey_andNeverThrows() {
        assertDoesNotThrow(() -> service.removeFromWatchlist(USER_ID, TMDB_ID));

        verify(watchlistItemRepository).deleteByKey(KEY);
    }

    @Test
    void markSeen_notYetSeen_addsToSeenAndRemovesFromWatchlist() {
        when(catalogFacade.getOrFetch(TMDB_ID, ContentLang.RU)).thenReturn(movieDto(TMDB_ID));
        when(seenItemRepository.existsById(KEY)).thenReturn(false);
        when(seenItemRepository.countByKeyUserId(USER_ID)).thenReturn(0L);

        service.markSeen(USER_ID, TMDB_ID, ContentLang.RU);

        ArgumentCaptor<SeenItem> captor = ArgumentCaptor.forClass(SeenItem.class);
        verify(seenItemRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getKey()).isEqualTo(KEY);
        assertThat(captor.getValue().getMarkedAt()).isEqualTo(CLOCK.instant());
        verify(watchlistItemRepository).deleteByKey(KEY);
    }

    @Test
    void markSeen_alreadyMarkedSeen_isNoOp() {
        when(catalogFacade.getOrFetch(TMDB_ID, ContentLang.RU)).thenReturn(movieDto(TMDB_ID));
        when(seenItemRepository.existsById(KEY)).thenReturn(true);

        assertDoesNotThrow(() -> service.markSeen(USER_ID, TMDB_ID, ContentLang.RU));

        verify(seenItemRepository, never()).saveAndFlush(any());
        verify(seenItemRepository, never()).countByKeyUserId(any());
        verify(watchlistItemRepository, never()).deleteByKey(any());
    }

    @Test
    void markSeen_countBelowSeenMaxButAtOrAboveWatchlistMax_stillSucceeds() {
        when(catalogFacade.getOrFetch(TMDB_ID, ContentLang.RU)).thenReturn(movieDto(TMDB_ID));
        when(seenItemRepository.existsById(KEY)).thenReturn(false);
        when(seenItemRepository.countByKeyUserId(USER_ID)).thenReturn(4L);

        assertDoesNotThrow(() -> service.markSeen(USER_ID, TMDB_ID, ContentLang.RU));

        verify(seenItemRepository).saveAndFlush(any());
    }

    @Test
    void markSeen_seenLimitReached_throwsDomainRuleException_andDoesNotInsert() {
        when(catalogFacade.getOrFetch(TMDB_ID, ContentLang.RU)).thenReturn(movieDto(TMDB_ID));
        when(seenItemRepository.existsById(KEY)).thenReturn(false);
        when(seenItemRepository.countByKeyUserId(USER_ID)).thenReturn((long) SEEN_MAX);

        assertThrows(DomainRuleException.class, () -> service.markSeen(USER_ID, TMDB_ID, ContentLang.RU));

        verify(seenItemRepository, never()).saveAndFlush(any());
        verify(watchlistItemRepository, never()).deleteByKey(any());
    }

    @Test
    void markSeen_concurrentInsertWinsRace_convergesWithoutThrowing() {
        when(catalogFacade.getOrFetch(TMDB_ID, ContentLang.RU)).thenReturn(movieDto(TMDB_ID));
        when(seenItemRepository.existsById(KEY)).thenReturn(false, true);
        when(seenItemRepository.countByKeyUserId(USER_ID)).thenReturn(0L);
        when(seenItemRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup key"));

        assertDoesNotThrow(() -> service.markSeen(USER_ID, TMDB_ID, ContentLang.RU));

        verify(seenItemRepository, times(2)).existsById(KEY);
    }

    @Test
    void unmarkSeen_deletesFromSeenOnly_andNeverCallsCatalogOrWatchlist() {
        assertDoesNotThrow(() -> service.unmarkSeen(USER_ID, TMDB_ID));

        verify(seenItemRepository).deleteByKey(KEY);
        verifyNoInteractions(catalogFacade);
        verifyNoInteractions(watchlistItemRepository);
    }

    @Test
    void getFlags_fourMovies_returnsCorrectCombinationForEach() {
        long nowhereId = 1L;
        long inWatchlistId = 2L;
        long seenId = 3L;
        long bothId = 4L;
        List<Long> requested = List.of(nowhereId, inWatchlistId, seenId, bothId);

        when(watchlistItemRepository.findByKeyUserIdAndKeyTmdbIdIn(USER_ID, requested))
                .thenReturn(List.of(
                        new WatchlistItem(USER_ID, inWatchlistId, CLOCK), new WatchlistItem(USER_ID, bothId, CLOCK)));
        when(seenItemRepository.findByKeyUserIdAndKeyTmdbIdIn(USER_ID, requested))
                .thenReturn(List.of(new SeenItem(USER_ID, seenId, CLOCK), new SeenItem(USER_ID, bothId, CLOCK)));

        Map<Long, MovieFlags> flags = service.getFlags(USER_ID, requested);

        assertThat(flags)
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        nowhereId, new MovieFlags(false, false),
                        inWatchlistId, new MovieFlags(true, false),
                        seenId, new MovieFlags(false, true),
                        bothId, new MovieFlags(true, true)));
    }

    @Test
    void getFlags_emptyIdSet_returnsEmptyMap_withoutQueryingRepositories() {
        Map<Long, MovieFlags> flags = service.getFlags(USER_ID, List.of());

        assertThat(flags).isEmpty();
        verifyNoInteractions(watchlistItemRepository);
        verifyNoInteractions(seenItemRepository);
    }

    @Test
    void aggregateFor_delegatesToRepository_andKeysResultByTmdbId() {
        UUID member1 = UUID.randomUUID();
        UUID member2 = UUID.randomUUID();
        Instant median = Instant.parse("2026-02-01T00:00:00Z");
        WatchlistAgg agg1 = new WatchlistAgg(701L, 2, median, 0);
        WatchlistAgg agg2 = new WatchlistAgg(702L, 1, median, 1);
        when(watchlistAggRepository.aggregateFor(List.of(member1, member2))).thenReturn(List.of(agg1, agg2));

        Map<Long, WatchlistAgg> result = service.aggregateFor(List.of(member1, member2));

        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(701L, agg1, 702L, agg2));
    }

    @Test
    void removeAndMarkSeen_delegatesToBulkRepository_withCurrentClockTime() {
        List<UUID> memberIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        service.removeAndMarkSeen(memberIds, TMDB_ID);

        verify(watchlistBulkRepository).removeAndMarkSeen(memberIds, TMDB_ID, CLOCK.instant());
    }

    @Test
    void getWatchlist_delegatesToRepositoryWithZeroBasedPageAndRequestedSize() {
        Page<WatchlistItem> page = new PageImpl<>(List.of(new WatchlistItem(USER_ID, TMDB_ID, CLOCK)));
        when(watchlistItemRepository.findByKeyUserIdOrderByAddedAtDesc(USER_ID, PageRequest.of(0, 20)))
                .thenReturn(page);

        Page<WatchlistItem> result = service.getWatchlist(USER_ID, 0, 20);

        assertThat(result).isSameAs(page);
    }

    @Test
    void getSeenItems_delegatesToRepositoryWithZeroBasedPageAndRequestedSize() {
        Page<SeenItem> page = new PageImpl<>(List.of(new SeenItem(USER_ID, TMDB_ID, CLOCK)));
        when(seenItemRepository.findByKeyUserIdOrderByMarkedAtDesc(USER_ID, PageRequest.of(1, 10)))
                .thenReturn(page);

        Page<SeenItem> result = service.getSeenItems(USER_ID, 1, 10);

        assertThat(result).isSameAs(page);
    }

    @Test
    void getFlag_movieInNeitherList_returnsBothFlagsFalse() {
        when(watchlistItemRepository.findByKeyUserIdAndKeyTmdbIdIn(USER_ID, List.of(TMDB_ID)))
                .thenReturn(List.of());
        when(seenItemRepository.findByKeyUserIdAndKeyTmdbIdIn(USER_ID, List.of(TMDB_ID)))
                .thenReturn(List.of());

        MovieFlags flag = service.getFlag(USER_ID, TMDB_ID);

        assertThat(flag).isEqualTo(new MovieFlags(false, false));
    }

    @Test
    void getFlag_movieInWatchlist_returnsInWatchlistTrue() {
        when(watchlistItemRepository.findByKeyUserIdAndKeyTmdbIdIn(USER_ID, List.of(TMDB_ID)))
                .thenReturn(List.of(new WatchlistItem(USER_ID, TMDB_ID, CLOCK)));
        when(seenItemRepository.findByKeyUserIdAndKeyTmdbIdIn(USER_ID, List.of(TMDB_ID)))
                .thenReturn(List.of());

        MovieFlags flag = service.getFlag(USER_ID, TMDB_ID);

        assertThat(flag).isEqualTo(new MovieFlags(true, false));
    }

    @Test
    void watchlistCount_delegatesToRepository() {
        when(watchlistItemRepository.countByKeyUserId(USER_ID)).thenReturn(7L);

        assertThat(service.watchlistCount(USER_ID)).isEqualTo(7L);
    }

    @Test
    void seenCount_delegatesToRepository() {
        when(seenItemRepository.countByKeyUserId(USER_ID)).thenReturn(3L);

        assertThat(service.seenCount(USER_ID)).isEqualTo(3L);
    }
}
