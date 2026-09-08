package app.quorum.auth.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TelegramUser(
        Long id,
        @JsonProperty("first_name") String firstName,
        @JsonProperty("last_name") String lastName,
        @JsonProperty("username") String userName,
        @JsonProperty("photo_url") String photoUrl,
        @JsonProperty("language_code") String languageCode,
        @JsonProperty("allows_write_to_pm") Boolean allowsWriteToPm) {}
