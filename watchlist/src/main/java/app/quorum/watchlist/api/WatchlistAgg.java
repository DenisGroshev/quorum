package app.quorum.watchlist.api;

import java.time.Instant;

public record WatchlistAgg(long tmdbId, int ownersCount, Instant medianAddedAt, int seenCount) {}
