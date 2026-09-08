package app.quorum.auth.api;

public record TelegramProfile(
        Long tgUserId,
        String displayName,
        String username,
        String avatarUrl,
        String languageCode,
        Boolean allowsWriteToPm) {

    public TelegramProfile(Long tgUserId, String displayName, String username, String avatarUrl, String languageCode) {
        this(tgUserId, displayName, username, avatarUrl, languageCode, null);
    }
}
