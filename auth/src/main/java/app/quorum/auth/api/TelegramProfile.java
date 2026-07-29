package app.quorum.auth.api;

public record TelegramProfile(
        Long tgUserId, String displayName, String username, String avatarUrl, String languageCode) {}
