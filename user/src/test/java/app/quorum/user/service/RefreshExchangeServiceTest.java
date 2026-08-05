package app.quorum.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.quorum.auth.api.AccessToken;
import app.quorum.auth.api.TelegramProfile;
import app.quorum.auth.domain.RefreshToken;
import app.quorum.auth.service.JwtService;
import app.quorum.auth.service.RefreshTokenService;
import app.quorum.common.exception.InvalidRefreshTokenException;
import app.quorum.common.exception.RefreshTokenReusedException;
import app.quorum.user.api.TelegramLoginResult;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefreshExchangeServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private JwtService jwtService;

    @Mock
    private UserRepository userRepository;

    private RefreshExchangeService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new RefreshExchangeService(refreshTokenService, jwtService, userRepository, clock);
        user = User.registerFromTelegram(new TelegramProfile(1L, "A", "a", null, "ru"), clock);
    }

    @Test
    void exchange_validToken_rotatesAndReturnsNewPair() {
        RefreshToken token = RefreshToken.issue(user.getId(), "hash", 30, clock);
        when(refreshTokenService.findByRawToken("raw-token")).thenReturn(token);
        when(refreshTokenService.rotate(token)).thenReturn("new-raw-refresh-token");
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(jwtService.issueAccessToken(user.getId(), user.getTokenVer())).thenReturn(new AccessToken("jwt", 3600));

        TelegramLoginResult result = service.exchange("raw-token");

        assertThat(result.accessToken()).isEqualTo("jwt");
        assertThat(result.refreshToken()).isEqualTo("new-raw-refresh-token");
        assertThat(result.expiresInSeconds()).isEqualTo(3600);
        verify(refreshTokenService, never()).revokeAllForUser(any());
    }

    @Test
    void exchange_unknownToken_throwsInvalidRefreshToken() {
        when(refreshTokenService.findByRawToken("bogus")).thenReturn(null);

        assertThrows(InvalidRefreshTokenException.class, () -> service.exchange("bogus"));
    }

    @Test
    void exchange_expiredButNotRevokedToken_throwsInvalidRefreshToken() {
        Clock expiredClock = Clock.fixed(NOW.minus(Duration.ofDays(31)), ZoneOffset.UTC);
        RefreshToken expiredToken = RefreshToken.issue(user.getId(), "hash", 30, expiredClock);
        when(refreshTokenService.findByRawToken("expired")).thenReturn(expiredToken);

        assertThrows(InvalidRefreshTokenException.class, () -> service.exchange("expired"));
    }

    @Test
    void exchange_revokedToken_throwsReusedAndRevokesAllAndBumpsTokenVersion() {
        RefreshToken token = RefreshToken.issue(user.getId(), "hash", 30, clock);
        token.revoke(UUID.randomUUID());
        when(refreshTokenService.findByRawToken("stolen")).thenReturn(token);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThrows(RefreshTokenReusedException.class, () -> service.exchange("stolen"));

        int tokenVerBeforeBump = 0;
        verify(refreshTokenService, times(1)).revokeAllForUser(user.getId());
        assertThat(user.getTokenVer()).isGreaterThan(tokenVerBeforeBump);
        verify(userRepository).save(user);
    }
}
