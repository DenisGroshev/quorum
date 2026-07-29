package app.quorum.user.api;

public record TelegramLoginResult(String accessToken, String refreshToken, long expiresInSeconds) {}
