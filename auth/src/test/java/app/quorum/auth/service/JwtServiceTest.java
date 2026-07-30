package app.quorum.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.quorum.auth.api.AccessToken;
import app.quorum.auth.api.AccessTokenClaims;
import app.quorum.common.exception.InvalidAccessTokenException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "test-jwt-secret-should-be-long-enough-for-hs256";
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final Duration ACCESS_TTL = Duration.ofHours(1);

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final JwtService service = new JwtService(clock, SECRET, ACCESS_TTL);

    @Test
    void issueThenParse_roundTrips_withMatchingClaims() {
        UUID userId = UUID.randomUUID();

        AccessToken issued = service.issueAccessToken(userId, 3);
        AccessTokenClaims claims = service.parseAccessToken(issued.value());

        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.tokenVer()).isEqualTo(3);
        assertThat(issued.expiresInSeconds()).isEqualTo(ACCESS_TTL.toSeconds());
    }

    @Test
    void parseAccessToken_expiredToken_throws() {
        UUID userId = UUID.randomUUID();
        AccessToken issued = service.issueAccessToken(userId, 1);

        Clock later = Clock.fixed(NOW.plus(ACCESS_TTL).plusSeconds(1), ZoneOffset.UTC);
        JwtService serviceAfterExpiry = new JwtService(later, SECRET, ACCESS_TTL);

        assertThrows(InvalidAccessTokenException.class, () -> serviceAfterExpiry.parseAccessToken(issued.value()));
    }

    @Test
    void parseAccessToken_signedWithDifferentKey_throws() {
        JwtService otherService = new JwtService(clock, "a-completely-different-signing-secret-value", ACCESS_TTL);
        AccessToken issuedByOther = otherService.issueAccessToken(UUID.randomUUID(), 1);

        assertThrows(InvalidAccessTokenException.class, () -> service.parseAccessToken(issuedByOther.value()));
    }

    @Test
    void parseAccessToken_malformedToken_throws() {
        assertThrows(InvalidAccessTokenException.class, () -> service.parseAccessToken("not-a-jwt-at-all"));
    }
}
