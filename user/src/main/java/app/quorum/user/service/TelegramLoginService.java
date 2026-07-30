package app.quorum.user.service;

import app.quorum.auth.api.AccessToken;
import app.quorum.auth.api.TelegramProfile;
import app.quorum.auth.api.TelegramUser;
import app.quorum.auth.service.JwtService;
import app.quorum.auth.service.RefreshTokenService;
import app.quorum.auth.service.RequestingUserService;
import app.quorum.user.api.TelegramLoginResult;
import app.quorum.user.domain.User;
import org.springframework.stereotype.Service;

@Service
public class TelegramLoginService {

    private final RequestingUserService requestingUserService;
    private final UserRegistrationService userRegistrationService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public TelegramLoginService(
            RequestingUserService requestingUserService,
            UserRegistrationService userRegistrationService,
            JwtService jwtService,
            RefreshTokenService refreshTokenService) {
        this.requestingUserService = requestingUserService;
        this.userRegistrationService = userRegistrationService;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    public TelegramLoginResult login(String rawInitData) {
        TelegramUser telegramUser = requestingUserService.authenticate(rawInitData);
        TelegramProfile profile = toProfile(telegramUser);

        User user = userRegistrationService.registerOrSync(profile);

        AccessToken accessToken = jwtService.issueAccessToken(user.getId(), user.getTokenVer());
        String refreshToken = refreshTokenService.issue(user.getId());

        return new TelegramLoginResult(accessToken.value(), refreshToken, accessToken.expiresInSeconds());
    }

    private TelegramProfile toProfile(TelegramUser telegramUser) {
        String displayName = telegramUser.firstName();
        if (telegramUser.lastName() != null && !telegramUser.lastName().isBlank()) {
            displayName = displayName + " " + telegramUser.lastName();
        }
        return new TelegramProfile(
                telegramUser.id(),
                displayName,
                telegramUser.userName(),
                telegramUser.photoUrl(),
                telegramUser.languageCode());
    }
}
