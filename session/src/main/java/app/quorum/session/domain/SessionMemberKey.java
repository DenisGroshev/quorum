package app.quorum.session.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

@Embeddable
public record SessionMemberKey(@Column(name = "session_id") UUID sessionId, @Column(name = "user_id") UUID userId)
        implements Serializable {}
