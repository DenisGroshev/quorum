package app.quorum.user.service;

import app.quorum.auth.api.AccessToken;
import app.quorum.auth.api.TelegramProfile;
import app.quorum.auth.api.TelegramUser;
import app.quorum.auth.service.JwtService;
import app.quorum.auth.service.RefreshTokenService;
import app.quorum.auth.service.RequestingUserService;
import app.quorum.user.api.TelegramLoginResult;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TelegramLoginService {

    private final RequestingUserService requestingUserService;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final Clock clock;

    public TelegramLoginService(
            RequestingUserService requestingUserService,
            UserRepository userRepository,
            JwtService jwtService,
            RefreshTokenService refreshTokenService,
            Clock clock) {
        this.requestingUserService = requestingUserService;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.clock = clock;
    }

    @Transactional
    public TelegramLoginResult login(String rawInitData) {
        TelegramUser telegramUser = requestingUserService.authenticate(rawInitData);
        TelegramProfile profile = toProfile(telegramUser);

        User user = userRepository.findByTgUserId(profile.tgUserId()).orElse(null);
        if (user == null) {
            user = User.registerFromTelegram(profile, clock);
        } else {
            user.syncFromTelegram(profile);
        }
        user = userRepository.save(user);

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
