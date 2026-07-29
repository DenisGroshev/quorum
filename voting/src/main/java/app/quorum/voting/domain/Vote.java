package app.quorum.voting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "votes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Vote {

    @EmbeddedId
    private VoteKey key;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private VoteValue value;

    @Column(name = "voted_at", nullable = false)
    private Instant votedAt;

    public Vote(VoteKey key, VoteValue value, Clock clock) {
        this.key = key;
        this.value = value;
        this.votedAt = clock.instant();
    }

    public void change(VoteValue newValue, Clock clock) {
        this.value = newValue;
        this.votedAt = clock.instant();
    }
}
