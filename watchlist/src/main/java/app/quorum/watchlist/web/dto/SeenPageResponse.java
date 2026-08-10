package app.quorum.watchlist.web.dto;

import app.quorum.watchlist.domain.SeenItem;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;

public record SeenPageResponse(List<Item> items, int page, int size, long totalElements, boolean hasNext) {

    public record Item(long tmdbId, Instant markedAt) {}

    public static SeenPageResponse from(Page<SeenItem> page) {
        List<Item> items = page.getContent().stream()
                .map(item -> new Item(item.getKey().tmdbId(), item.getMarkedAt()))
                .toList();
        return new SeenPageResponse(items, page.getNumber(), page.getSize(), page.getTotalElements(), page.hasNext());
    }
}
