package app.quorum.auth.service;

import app.quorum.auth.domain.RefreshToken;
import app.quorum.auth.repository.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final Clock clock;
    private final Duration refreshTtl;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(
            RefreshTokenRepository repository,
            Clock clock,
            @Value("${quorum.auth.jwt.refresh-ttl}") Duration refreshTtl) {
        this.repository = repository;
        this.clock = clock;
        this.refreshTtl = refreshTtl;
    }

    public String issue(UUID userId) {
        String rawToken = generateRawToken();
        RefreshToken entity = RefreshToken.issue(userId, sha256Hex(rawToken), (int) refreshTtl.toDays(), clock);
        repository.save(entity);
        return rawToken;
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
