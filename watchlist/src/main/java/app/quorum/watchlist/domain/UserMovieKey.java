package app.quorum.watchlist.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

@Embeddable
public record UserMovieKey(@Column(name = "user_id") UUID userId, @Column(name = "tmdb_id") Long tmdbId)
        implements Serializable {}
