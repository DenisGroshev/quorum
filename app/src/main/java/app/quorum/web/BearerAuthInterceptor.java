package app.quorum.web;

import app.quorum.common.exception.InvalidAccessTokenException;
import app.quorum.user.service.AccessTokenAuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;

public class BearerAuthInterceptor implements HandlerInterceptor {

    public static final String USER_ID_ATTRIBUTE = "authenticatedUserId";
    private static final String SCHEME_PREFIX = "Bearer ";

    private final AccessTokenAuthenticationService authenticationService;

    public BearerAuthInterceptor(AccessTokenAuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(SCHEME_PREFIX)) {
            throw new InvalidAccessTokenException("Authorization header must use the 'Bearer' scheme");
        }

        String token = header.substring(SCHEME_PREFIX.length());
        UUID userId = authenticationService.authenticate(token);
        request.setAttribute(USER_ID_ATTRIBUTE, userId);
        return true;
    }
}
