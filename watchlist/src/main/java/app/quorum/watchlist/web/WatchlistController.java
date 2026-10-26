package app.quorum.watchlist.web;

import app.quorum.common.ContentLang;
import app.quorum.common.web.RequestAttributes;
import app.quorum.watchlist.api.MovieFlags;
import app.quorum.watchlist.domain.WatchlistService;
import app.quorum.watchlist.web.dto.MovieFlagResponse;
import app.quorum.watchlist.web.dto.SeenPageResponse;
import app.quorum.watchlist.web.dto.WatchlistPageResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class WatchlistController {

    private final WatchlistService watchlistService;

    public WatchlistController(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    @GetMapping("/watchlist")
    public WatchlistPageResponse getWatchlist(
            @RequestAttribute(RequestAttributes.USER_ID_ATTRIBUTE) UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        validatePaging(page, size);
        return WatchlistPageResponse.from(watchlistService.getWatchlist(userId, page, size));
    }

    @PutMapping("/watchlist/{tmdbId}")
    public MovieFlagResponse addToWatchlist(
            @RequestAttribute(RequestAttributes.USER_ID_ATTRIBUTE) UUID userId,
            @PathVariable long tmdbId,
            @RequestAttribute(RequestAttributes.CONTENT_LANG_ATTRIBUTE) ContentLang lang,
            @RequestParam(required = false) String source) {
        watchlistService.addToWatchlist(userId, tmdbId, lang, source);
        return currentState(userId, tmdbId);
    }

    @DeleteMapping("/watchlist/{tmdbId}")
    public ResponseEntity<Void> removeFromWatchlist(
            @RequestAttribute(RequestAttributes.USER_ID_ATTRIBUTE) UUID userId, @PathVariable long tmdbId) {
        watchlistService.removeFromWatchlist(userId, tmdbId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/seen")
    public SeenPageResponse getSeen(
            @RequestAttribute(RequestAttributes.USER_ID_ATTRIBUTE) UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        validatePaging(page, size);
        return SeenPageResponse.from(watchlistService.getSeenItems(userId, page, size));
    }

    @PutMapping("/seen/{tmdbId}")
    public MovieFlagResponse markSeen(
            @RequestAttribute(RequestAttributes.USER_ID_ATTRIBUTE) UUID userId,
            @PathVariable long tmdbId,
            @RequestAttribute(RequestAttributes.CONTENT_LANG_ATTRIBUTE) ContentLang lang) {
        watchlistService.markSeen(userId, tmdbId, lang);
        return currentState(userId, tmdbId);
    }

    @DeleteMapping("/seen/{tmdbId}")
    public ResponseEntity<Void> unmarkSeen(
            @RequestAttribute(RequestAttributes.USER_ID_ATTRIBUTE) UUID userId, @PathVariable long tmdbId) {
        watchlistService.unmarkSeen(userId, tmdbId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/movies/flags")
    public Map<Long, MovieFlags> getFlags(
            @RequestAttribute(RequestAttributes.USER_ID_ATTRIBUTE) UUID userId, @RequestBody List<Long> tmdbIds) {
        return watchlistService.getFlags(userId, tmdbIds);
    }

    private MovieFlagResponse currentState(UUID userId, long tmdbId) {
        MovieFlags flags = watchlistService.getFlag(userId, tmdbId);
        return new MovieFlagResponse(
                flags.inWatchlist(),
                flags.seen(),
                watchlistService.watchlistCount(userId),
                watchlistService.seenCount(userId));
    }

    private static void validatePaging(int page, int size) {
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0");
        }
        if (size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be between 1 and 100");
        }
    }
}
