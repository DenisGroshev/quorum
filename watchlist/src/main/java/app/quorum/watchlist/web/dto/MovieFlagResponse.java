package app.quorum.watchlist.web.dto;

public record MovieFlagResponse(boolean inWatchlist, boolean seen, long watchlistCount, long seenCount) {}
