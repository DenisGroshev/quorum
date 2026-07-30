package app.quorum.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import app.quorum.auth.api.AccessTokenClaims;
import app.quorum.auth.service.JwtService;
import app.quorum.common.exception.InvalidAccessTokenException;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccessTokenAuthenticationServiceTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private UserRepository userRepository;

    private AccessTokenAuthenticationService service;

    @BeforeEach
    void setUp() {
        service = new AccessTokenAuthenticationService(jwtService, userRepository);
    }

    @Test
    void authenticate_tokenVerMatchesCurrentUser_returnsUserId() {
        UUID userId = UUID.randomUUID();
        User user = User.registerFromTelegram(
                new app.quorum.auth.api.TelegramProfile(1L, "A", "a", null, "ru"), Clock.systemUTC());
        when(jwtService.parseAccessToken("token")).thenReturn(new AccessTokenClaims(user.getId(), user.getTokenVer()));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        UUID resolved = service.authenticate("token");

        assertThat(resolved).isEqualTo(user.getId());
    }

    @Test
    void authenticate_tokenVerOlderThanCurrentUser_throws() {
        User user = User.registerFromTelegram(
                new app.quorum.auth.api.TelegramProfile(1L, "A", "a", null, "ru"), Clock.systemUTC());
        int staleTokenVer = user.getTokenVer();
        user.bumpTokenVersion();

        when(jwtService.parseAccessToken("token")).thenReturn(new AccessTokenClaims(user.getId(), staleTokenVer));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThrows(InvalidAccessTokenException.class, () -> service.authenticate("token"));
    }

    @Test
    void authenticate_userNoLongerExists_throws() {
        UUID userId = UUID.randomUUID();
        when(jwtService.parseAccessToken("token")).thenReturn(new AccessTokenClaims(userId, 0));
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        assertThrows(InvalidAccessTokenException.class, () -> service.authenticate("token"));
    }
}
