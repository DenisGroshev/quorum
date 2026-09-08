package app.quorum.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.auth.api.TelegramProfile;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class UserTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);

    private static TelegramProfile profile(Boolean allowsWriteToPm) {
        return new TelegramProfile(1L, "Denis", "denis", null, "ru", allowsWriteToPm);
    }

    @Test
    void registerFromTelegram_allowsWriteToPmTrue_notifyEnabledTrue() {
        User user = User.registerFromTelegram(profile(true), CLOCK);

        assertThat(user.isNotifyEnabled()).isTrue();
    }

    @Test
    void registerFromTelegram_allowsWriteToPmFalse_notifyEnabledFalse() {
        User user = User.registerFromTelegram(profile(false), CLOCK);

        assertThat(user.isNotifyEnabled()).isFalse();
    }

    @Test
    void registerFromTelegram_allowsWriteToPmNull_notifyEnabledFalse() {
        User user = User.registerFromTelegram(profile(null), CLOCK);

        assertThat(user.isNotifyEnabled()).isFalse();
    }

    @Test
    void syncFromTelegram_allowsWriteToPmProvided_updatesNotifyEnabled() {
        User user = User.registerFromTelegram(profile(true), CLOCK);

        user.syncFromTelegram(profile(false));

        assertThat(user.isNotifyEnabled()).isFalse();
    }

    @Test
    void syncFromTelegram_allowsWriteToPmNull_leavesNotifyEnabledUnchanged() {
        User user = User.registerFromTelegram(profile(true), CLOCK);

        user.syncFromTelegram(profile(null));

        assertThat(user.isNotifyEnabled()).isTrue();
    }
}
