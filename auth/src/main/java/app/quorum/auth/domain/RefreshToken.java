package app.quorum.auth.domain;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static RefreshToken issue(UUID userId, String tokenHash, int ttlDays, Clock clock) {
        RefreshToken rt = new RefreshToken();
        rt.id = UuidCreator.getTimeOrderedEpoch();
        rt.userId = userId;
        rt.tokenHash = tokenHash;
        rt.createdAt = clock.instant();
        rt.expiresAt = rt.createdAt.plus(ttlDays, ChronoUnit.DAYS);
        rt.revoked = false;
        return rt;
    }

    public boolean usable(Clock c) {
        return !revoked && c.instant().isBefore(expiresAt);
    }

    public void revoke(UUID replacedBy) {
        this.revoked = true;
        this.replacedBy = replacedBy;
    }
}
