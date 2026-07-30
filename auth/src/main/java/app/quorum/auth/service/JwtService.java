package app.quorum.auth.service;

import app.quorum.auth.api.AccessToken;
import app.quorum.auth.api.AccessTokenClaims;
import app.quorum.common.exception.InvalidAccessTokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final Clock clock;
    private final io.jsonwebtoken.Clock jwtClock;
    private final SecretKey signingKey;
    private final Duration accessTtl;

    public JwtService(
            Clock clock,
            @Value("${quorum.auth.jwt.secret}") String secret,
            @Value("${quorum.auth.jwt.access-ttl}") Duration accessTtl) {
        this.clock = clock;
        this.jwtClock = () -> Date.from(clock.instant());
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = accessTtl;
    }

    public AccessToken issueAccessToken(UUID userId, int tokenVer) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(accessTtl);

        String token = Jwts.builder()
                .subject(userId.toString())
                .claim("tokenVer", tokenVer)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();

        return new AccessToken(token, accessTtl.toSeconds());
    }

    public AccessTokenClaims parseAccessToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .clock(jwtClock)
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            UUID userId = UUID.fromString(claims.getSubject());
            int tokenVer = claims.get("tokenVer", Integer.class);
            return new AccessTokenClaims(userId, tokenVer);
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidAccessTokenException("Access token is invalid", e);
        }
    }
}
