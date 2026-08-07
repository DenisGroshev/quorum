package app.quorum.watchlist.web.dto;

import app.quorum.watchlist.domain.WatchlistItem;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;

public record WatchlistPageResponse(List<Item> items, int page, int size, long totalElements, boolean hasNext) {

    public record Item(long tmdbId, Instant addedAt) {}

    public static WatchlistPageResponse from(Page<WatchlistItem> page) {
        List<Item> items = page.getContent().stream()
                .map(item -> new Item(item.getKey().tmdbId(), item.getAddedAt()))
                .toList();
        return new WatchlistPageResponse(
                items, page.getNumber(), page.getSize(), page.getTotalElements(), page.hasNext());
    }
}
