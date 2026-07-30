package app.quorum.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.quorum.auth.api.TelegramProfile;
import app.quorum.auth.service.RefreshTokenService;
import app.quorum.common.ContentLang;
import app.quorum.common.exception.DomainRuleException;
import app.quorum.user.domain.User;
import app.quorum.user.repository.GenreRepository;
import app.quorum.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private UserRepository userRepository;

    @Mock
    private GenreRepository genreRepository;

    @Mock
    private RefreshTokenService refreshTokenService;

    private UserProfileService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new UserProfileService(userRepository, genreRepository, refreshTokenService, CLOCK);
        user = User.registerFromTelegram(new TelegramProfile(1L, "A", "a", null, "ru"), Clock.systemUTC());
    }

    @Test
    void replaceFavoriteGenres_tenExistingGenres_succeeds() {
        Set<Integer> genreIds = IntStream.rangeClosed(1, 10).boxed().collect(Collectors.toSet());
        when(genreRepository.countByIdIn(genreIds)).thenReturn(10L);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.replaceFavoriteGenres(user.getId(), genreIds);

        assertThat(user.getFavGenreIds()).isEqualTo(genreIds);
    }

    @Test
    void replaceFavoriteGenres_elevenGenres_throwsDomainRule() {
        Set<Integer> genreIds = IntStream.rangeClosed(1, 11).boxed().collect(Collectors.toSet());
        when(genreRepository.countByIdIn(genreIds)).thenReturn(11L);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThrows(DomainRuleException.class, () -> service.replaceFavoriteGenres(user.getId(), genreIds));
    }

    @Test
    void replaceFavoriteGenres_nonexistentGenreId_throwsDomainRule() {
        Set<Integer> genreIds = Set.of(1, 2, 999_999);
        when(genreRepository.countByIdIn(genreIds)).thenReturn(2L);

        assertThrows(DomainRuleException.class, () -> service.replaceFavoriteGenres(user.getId(), genreIds));
    }

    @Test
    void updateSettings_onlyNotifyEnabledProvided_leavesLangContentUnchanged() {
        ContentLang originalLang = user.getLangContent();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.updateSettings(user.getId(), false, null);

        assertThat(user.isNotifyEnabled()).isFalse();
        assertThat(user.getLangContent()).isEqualTo(originalLang);
    }

    @Test
    void updateSettings_onlyLangContentProvided_leavesNotifyEnabledUnchanged() {
        boolean originalNotify = user.isNotifyEnabled();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.updateSettings(user.getId(), null, "en");

        assertThat(user.getLangContent()).isEqualTo(ContentLang.EN);
        assertThat(user.isNotifyEnabled()).isEqualTo(originalNotify);
    }

    @Test
    void deleteAccount_anonymizesUserAndRevokesRefreshTokens() {
        int tokenVerBefore = user.getTokenVer();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.deleteAccount(user.getId());

        assertThat(user.getTgUserId()).isNull();
        assertThat(user.getDeletedAt()).isEqualTo(CLOCK.instant());
        assertThat(user.getTokenVer()).isGreaterThan(tokenVerBefore);
        verify(refreshTokenService, times(1)).revokeAllForUser(user.getId());
        verify(userRepository).save(user);
    }
}
