package app.quorum.user.service;

import app.quorum.auth.api.AccessToken;
import app.quorum.auth.domain.RefreshToken;
import app.quorum.auth.service.JwtService;
import app.quorum.auth.service.RefreshTokenService;
import app.quorum.common.exception.InvalidRefreshTokenException;
import app.quorum.common.exception.RefreshTokenReusedException;
import app.quorum.user.api.TelegramLoginResult;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshExchangeService {

    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final Clock clock;

    public RefreshExchangeService(
            RefreshTokenService refreshTokenService,
            JwtService jwtService,
            UserRepository userRepository,
            Clock clock) {
        this.refreshTokenService = refreshTokenService;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = RefreshTokenReusedException.class)
    public TelegramLoginResult exchange(String rawRefreshToken) {
        RefreshToken token = refreshTokenService.findByRawToken(rawRefreshToken);
        if (token == null) {
            throw new InvalidRefreshTokenException("Refresh token is invalid");
        }

        if (token.isRevoked()) {
            refreshTokenService.revokeAllForUser(token.getUserId());
            bumpTokenVersion(token.getUserId());
            throw new RefreshTokenReusedException("auth.refresh.reused");
        }

        if (!token.usable(clock)) {
            throw new InvalidRefreshTokenException("Refresh token has expired");
        }

        User user = userRepository
                .findById(token.getUserId())
                .orElseThrow(() -> new InvalidRefreshTokenException("User no longer exists"));

        String newRawRefreshToken = refreshTokenService.rotate(token);
        AccessToken accessToken = jwtService.issueAccessToken(user.getId(), user.getTokenVer());

        return new TelegramLoginResult(accessToken.value(), newRawRefreshToken, accessToken.expiresInSeconds());
    }

    private void bumpTokenVersion(UUID userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.bumpTokenVersion();
            userRepository.save(user);
        });
    }
}
