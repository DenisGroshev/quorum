package app.quorum.user.service;

import app.quorum.auth.api.AccessTokenClaims;
import app.quorum.auth.service.JwtService;
import app.quorum.common.exception.InvalidAccessTokenException;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AccessTokenAuthenticationService {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public AccessTokenAuthenticationService(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    public UUID authenticate(String rawAccessToken) {
        AccessTokenClaims claims = jwtService.parseAccessToken(rawAccessToken);

        User user = userRepository
                .findById(claims.userId())
                .orElseThrow(() -> new InvalidAccessTokenException("User no longer exists"));

        if (user.getTokenVer() != claims.tokenVer()) {
            throw new InvalidAccessTokenException("Access token has been superseded");
        }

        return user.getId();
    }
}
