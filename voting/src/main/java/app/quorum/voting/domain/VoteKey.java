package app.quorum.voting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

@Embeddable
public record VoteKey(
        @Column(name = "session_id") UUID sessionId,
        @Column(name = "user_id") UUID userId,
        @Column(name = "tmdb_id") Long tmdbId)
        implements Serializable {}
