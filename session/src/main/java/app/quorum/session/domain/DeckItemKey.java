package app.quorum.session.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

@Embeddable
public record DeckItemKey(@Column(name = "session_id") UUID sessionId, @Column(name = "tmdb_id") Long tmdbId)
        implements Serializable {}
