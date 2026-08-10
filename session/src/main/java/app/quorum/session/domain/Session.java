package app.quorum.session.domain;

import app.quorum.common.SessionState;
import app.quorum.common.exception.DomainRuleException;
import app.quorum.common.exception.SessionForbiddenException;
import app.quorum.common.exception.SessionStateConflictException;
import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Session implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "host_id", nullable = false)
    private UUID hostId;

    @Column(nullable = false, length = 128)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SessionState state;

    @Column(name = "invite_code", nullable = false, unique = true, length = 10)
    private String inviteCode;

    @Column(name = "deck_size", nullable = false)
    private short deckSize;

    @Column(name = "vote_deadline_minutes", nullable = false)
    private int voteDeadlineMinutes;

    @Column(name = "watchlist_only", nullable = false)
    private boolean watchlistOnly;

    @Column(name = "match_config_ver", length = 32)
    private String matchConfigVer;

    @Column(name = "voting_started_at")
    private Instant votingStartedAt;

    @Column(name = "voting_deadline_at")
    private Instant votingDeadlineAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "watched_at")
    private Instant watchedAt;

    @Column(name = "winner_tmdb_id")
    private Long winnerTmdbId;

    @Column(name = "winner_contested", nullable = false)
    private boolean winnerContested;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<SessionMember> members = new HashSet<>();

    @OneToMany(
            mappedBy = "session",
            cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @OrderBy("position ASC")
    private List<DeckItem> deck = new ArrayList<>();

    @Transient
    private boolean isNew;

    public static final int MAX_MEMBERS = 10;

    public static Session create(UUID hostId, SessionParams p, String inviteCode, Clock clock) {
        Session s = new Session();
        s.id = UuidCreator.getTimeOrderedEpoch();
        s.hostId = hostId;
        s.title = p.title();
        s.state = SessionState.LOBBY;
        s.inviteCode = inviteCode;
        s.deckSize = p.deckSize().shortValue();
        s.voteDeadlineMinutes = p.voteDeadlineMinutes();
        s.watchlistOnly = p.watchlistOnly();
        s.winnerContested = false;
        s.createdAt = clock.instant();
        s.isNew = true;
        s.members.add(SessionMember.host(s, hostId, clock));
        return s;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public void join(UUID userId, Clock clock) {
        requireState(SessionState.LOBBY, "session.not-joinable");
        if (isMember(userId)) return;
        if (members.size() >= MAX_MEMBERS) throw new DomainRuleException("session.full");
        members.add(SessionMember.regular(this, userId, clock));
    }

    public void startVoting(UUID actorId, DeckResult deckResult, String matchConfigVer, Clock clock) {
        requireHost(actorId);
        requireState(SessionState.LOBBY, "session.illegal-transition");
        if (members.size() < 2) throw new DomainRuleException("session.not-enough-members");
        transitionTo(SessionState.VOTING);
        this.matchConfigVer = matchConfigVer;
        this.votingStartedAt = clock.instant();
        this.votingDeadlineAt = votingStartedAt.plus(Duration.ofMinutes(voteDeadlineMinutes));
        int pos = 0;
        for (ScoredMovie m : deckResult.movies())
            deck.add(new DeckItem(this, m.tmdbId(), pos++, m.score(), m.reasonsJson()));
    }

    public void decide(Long winnerTmdbId, boolean contested, Clock clock) {
        requireState(SessionState.VOTING, "session.illegal-transition");
        transitionTo(SessionState.DECIDED);
        this.winnerTmdbId = winnerTmdbId;
        this.winnerContested = contested;
        this.decidedAt = clock.instant();
    }

    public void markWatched(UUID actorId, Clock clock) {
        requireHost(actorId);
        requireState(SessionState.DECIDED, "session.illegal-transition");
        transitionTo(SessionState.WATCHED);
        this.watchedAt = clock.instant();
    }

    public void cancel(UUID actorId) {
        requireHost(actorId);
        if (state != SessionState.LOBBY && state != SessionState.VOTING)
            throw new SessionStateConflictException("session.illegal-transition");
        transitionTo(SessionState.CANCELLED);
    }

    public void updateParams(UUID actorId, SessionParams params) {
        requireHost(actorId);
        requireState(SessionState.LOBBY, "session.illegal-transition");
        this.title = params.title();
        this.deckSize = params.deckSize().shortValue();
        this.voteDeadlineMinutes = params.voteDeadlineMinutes();
        this.watchlistOnly = params.watchlistOnly();
    }

    public void expire() {
        requireState(SessionState.LOBBY, "session.illegal-transition");
        transitionTo(SessionState.EXPIRED);
    }

    public void archive() {
        requireState(SessionState.DECIDED, "session.illegal-transition");
        transitionTo(SessionState.ARCHIVED);
    }

    public void rateBy(UUID actorId, short value, Clock clock) {
        requireState(SessionState.WATCHED, "session.illegal-transition");
        SessionMember member = members.stream()
                .filter(m -> m.getUserId().equals(actorId))
                .findFirst()
                .orElseThrow(() -> new SessionForbiddenException("session.forbidden"));
        member.rate(value, clock, watchedAt);
    }

    public void kick(UUID actorId, UUID targetId) {
        requireHost(actorId);
        requireState(SessionState.LOBBY, "session.illegal-transition");
        if (targetId.equals(hostId)) throw new DomainRuleException("session.forbidden");
        members.removeIf(m -> m.getUserId().equals(targetId));
    }

    public boolean isMember(UUID userId) {
        return members.stream().anyMatch(m -> m.getUserId().equals(userId));
    }

    private void transitionTo(SessionState target) {
        if (!state.canTransitionTo(target)) throw new SessionStateConflictException("session.illegal-transition");
        this.state = target;
    }

    private void requireState(SessionState expected, String code) {
        if (state != expected) throw new SessionStateConflictException(code);
    }

    private void requireHost(UUID actorId) {
        if (!hostId.equals(actorId)) throw new SessionForbiddenException("session.forbidden");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Session other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Session.class.hashCode();
    }
}
