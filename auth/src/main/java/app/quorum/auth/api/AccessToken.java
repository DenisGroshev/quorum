package app.quorum.auth.api;

public record AccessToken(String value, long expiresInSeconds) {}
