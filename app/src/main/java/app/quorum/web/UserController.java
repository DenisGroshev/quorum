package app.quorum.web;

import app.quorum.common.exception.InvalidAccessTokenException;
import app.quorum.session.domain.SessionQueryService;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import app.quorum.user.service.UserProfileService;
import app.quorum.watchlist.api.WatchlistQueries;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserRepository userRepository;
    private final UserProfileService userProfileService;
    private final WatchlistQueries watchlistQueries;
    private final SessionQueryService sessionQueryService;

    public UserController(
            UserRepository userRepository,
            UserProfileService userProfileService,
            WatchlistQueries watchlistQueries,
            SessionQueryService sessionQueryService) {
        this.userRepository = userRepository;
        this.userProfileService = userProfileService;
        this.watchlistQueries = watchlistQueries;
        this.sessionQueryService = sessionQueryService;
    }

    @GetMapping("/me")
    public UserProfileResponse me(@RequestAttribute(BearerAuthInterceptor.USER_ID_ATTRIBUTE) UUID userId) {
        User user = userRepository
                .findById(userId)
                .orElseThrow(() -> new InvalidAccessTokenException("User no longer exists"));
        return new UserProfileResponse(
                user.getId(),
                user.getDisplayName(),
                user.getUsername(),
                user.isNotifyEnabled(),
                user.getLangContent().name(),
                watchlistQueries.watchlistCount(userId),
                sessionQueryService.countWatched(userId));
    }

    @PutMapping("/me/favorite-genres")
    public ResponseEntity<Void> replaceFavoriteGenres(
            @RequestAttribute(BearerAuthInterceptor.USER_ID_ATTRIBUTE) UUID userId,
            @RequestBody ReplaceFavoriteGenresRequest request) {
        userProfileService.replaceFavoriteGenres(userId, request.genreIds());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/me/settings")
    public ResponseEntity<Void> updateSettings(
            @RequestAttribute(BearerAuthInterceptor.USER_ID_ATTRIBUTE) UUID userId,
            @RequestBody UpdateSettingsRequest request) {
        userProfileService.updateSettings(userId, request.notifyEnabled(), request.langContent());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteAccount(@RequestAttribute(BearerAuthInterceptor.USER_ID_ATTRIBUTE) UUID userId) {
        userProfileService.deleteAccount(userId);
        return ResponseEntity.noContent().build();
    }
}
