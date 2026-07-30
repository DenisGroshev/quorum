package app.quorum.user.service;

import app.quorum.auth.service.RefreshTokenService;
import app.quorum.common.ContentLang;
import app.quorum.common.exception.DomainRuleException;
import app.quorum.common.exception.InvalidAccessTokenException;
import app.quorum.user.domain.User;
import app.quorum.user.repository.GenreRepository;
import app.quorum.user.repository.UserRepository;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProfileService {

    private final UserRepository userRepository;
    private final GenreRepository genreRepository;
    private final RefreshTokenService refreshTokenService;
    private final Clock clock;

    public UserProfileService(
            UserRepository userRepository,
            GenreRepository genreRepository,
            RefreshTokenService refreshTokenService,
            Clock clock) {
        this.userRepository = userRepository;
        this.genreRepository = genreRepository;
        this.refreshTokenService = refreshTokenService;
        this.clock = clock;
    }

    @Transactional
    public void replaceFavoriteGenres(UUID userId, Set<Integer> genreIds) {
        if (!genreIds.isEmpty() && genreRepository.countByIdIn(genreIds) != genreIds.size()) {
            throw new DomainRuleException("user.genre-not-found");
        }

        User user = findUser(userId);
        user.replaceFavGenres(genreIds);
        userRepository.save(user);
    }

    @Transactional
    public void updateSettings(UUID userId, Boolean notifyEnabled, String langContentCode) {
        ContentLang langContent = parseLangContent(langContentCode);

        User user = findUser(userId);
        user.updateSettings(notifyEnabled, langContent);
        userRepository.save(user);
    }

    @Transactional
    public void deleteAccount(UUID userId) {
        User user = findUser(userId);
        user.anonymize(clock);
        userRepository.save(user);
        refreshTokenService.revokeAllForUser(userId);
    }

    private ContentLang parseLangContent(String langContentCode) {
        if (langContentCode == null) {
            return null;
        }
        try {
            return ContentLang.valueOf(langContentCode.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new DomainRuleException("user.lang-content-invalid");
        }
    }

    private User findUser(UUID userId) {
        return userRepository
                .findById(userId)
                .orElseThrow(() -> new InvalidAccessTokenException("User no longer exists"));
    }
}
