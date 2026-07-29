package app.quorum.session.domain;

import app.quorum.common.exception.DomainRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "session_members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SessionMember {

    @EmbeddedId
    private SessionMemberKey key;

    @MapsId("sessionId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private Session session;

    @Column(name = "is_host", nullable = false)
    private boolean host;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "completed_voting_at")
    private Instant completedVotingAt;

    @Column
    private Short rating;

    public UUID getUserId() {
        return key.userId();
    }

    static SessionMember host(Session s, UUID userId, Clock c) {
        return of(s, userId, true, c);
    }

    static SessionMember regular(Session s, UUID userId, Clock c) {
        return of(s, userId, false, c);
    }

    public void markVotingCompleted(Clock c) {
        this.completedVotingAt = c.instant();
    }

    public void rate(short value, Clock clock, Instant watchedAt) {
        if (value < 1 || value > 5) throw new DomainRuleException("validation.failed");
        if (clock.instant().isAfter(watchedAt.plus(Duration.ofHours(48))))
            throw new DomainRuleException("session.rating-window-closed");
        this.rating = value;
    }

    private static SessionMember of(Session s, UUID userId, boolean isHost, Clock c) {
        SessionMember m = new SessionMember();
        m.key = new SessionMemberKey(s.getId(), userId);
        m.session = s;
        m.host = isHost;
        m.joinedAt = c.instant();
        return m;
    }
}
