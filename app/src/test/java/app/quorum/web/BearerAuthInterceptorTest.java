package app.quorum.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import app.quorum.common.ContentLang;
import app.quorum.common.exception.InvalidAccessTokenException;
import app.quorum.common.web.RequestAttributes;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import app.quorum.user.service.AccessTokenAuthenticationService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class BearerAuthInterceptorTest {

    @Mock
    private AccessTokenAuthenticationService authenticationService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private User user;

    private BearerAuthInterceptor interceptor() {
        return new BearerAuthInterceptor(authenticationService, userRepository);
    }

    @Test
    void preHandle_setsUserIdAndResolvedContentLangAttributes() {
        UUID userId = UUID.randomUUID();
        when(authenticationService.authenticate("valid-token")).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(user.getLangContent()).thenReturn(ContentLang.EN);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");

        boolean result = interceptor().preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(result).isTrue();
        assertThat(request.getAttribute(BearerAuthInterceptor.USER_ID_ATTRIBUTE))
                .isEqualTo(userId);
        assertThat(request.getAttribute(RequestAttributes.CONTENT_LANG_ATTRIBUTE))
                .isEqualTo(ContentLang.EN);
    }

    @Test
    void preHandle_missingBearerScheme_throwsWithoutTouchingUserRepository() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic something");

        assertThatThrownBy(() -> interceptor().preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(InvalidAccessTokenException.class);
    }
}
