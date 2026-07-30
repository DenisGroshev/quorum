package app.quorum.web;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.user.api.TelegramLoginResult;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full path: login via initData -> read profile with the issued token -> change
 * settings -> read profile again and see the change reflected.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndScenarioIT {

    private static final String BOT_TOKEN = "it-test-bot-token";

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("TG_BOT_TOKEN", () -> BOT_TOKEN);
        registry.add("JWT_SECRET", () -> "integration-test-jwt-secret-should-be-long-enough");
        registry.add("TG_APP_NAME", () -> "quorum-it-tests");
        registry.add("TMDB_TOKEN", () -> "unused-in-this-test");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void fullPath_loginThenProfileThenSettingsChangeThenProfileReflectsIt() {
        TelegramLoginResult login = login(555_001L, "Mia", "mia");

        Map<String, Object> initialProfile = getProfile(login.accessToken());
        assertThat(initialProfile.get("displayName")).isEqualTo("Mia");
        assertThat(initialProfile.get("notifyEnabled")).isEqualTo(true);
        assertThat(initialProfile.get("langContent")).isEqualTo("RU");

        ResponseEntity<Void> settingsResponse =
                patchSettings(login.accessToken(), new UpdateSettingsRequest(false, "en"));
        assertThat(settingsResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Map<String, Object> updatedProfile = getProfile(login.accessToken());
        assertThat(updatedProfile.get("notifyEnabled")).isEqualTo(false);
        assertThat(updatedProfile.get("langContent")).isEqualTo("EN");
        // unrelated fields must survive the partial update untouched
        assertThat(updatedProfile.get("displayName")).isEqualTo("Mia");
    }

    private TelegramLoginResult login(long tgUserId, String firstName, String username) {
        String userJson = "{\"id\":" + tgUserId + ",\"first_name\":\"" + firstName + "\",\"username\":\"" + username
                + "\",\"language_code\":\"ru\"}";
        String initData =
                sign(Map.of("auth_date", String.valueOf(Instant.now().getEpochSecond()), "user", userJson), BOT_TOKEN);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "tma " + initData);

        ResponseEntity<TelegramLoginResult> response = restTemplate.exchange(
                "/api/v1/auth/telegram", HttpMethod.POST, new HttpEntity<Void>(headers), TelegramLoginResult.class);
        return response.getBody();
    }

    private Map<String, Object> getProfile(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.GET,
                new HttpEntity<Void>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        return body;
    }

    private ResponseEntity<Void> patchSettings(String accessToken, UpdateSettingsRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        HttpEntity<UpdateSettingsRequest> entity = new HttpEntity<>(request, headers);

        return restTemplate.exchange("/api/v1/users/me/settings", HttpMethod.PATCH, entity, Void.class);
    }

    private static String sign(Map<String, String> fields, String botToken) {
        Map<String, String> ordered = new LinkedHashMap<>(fields);

        String dataCheckString = ordered.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\n"));

        byte[] secretKey =
                hmac("WebAppData".getBytes(StandardCharsets.UTF_8), botToken.getBytes(StandardCharsets.UTF_8));
        byte[] hash = hmac(secretKey, dataCheckString.getBytes(StandardCharsets.UTF_8));
        String hashHex = HexFormat.of().formatHex(hash);

        String query = ordered.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));

        return query + "&hash=" + hashHex;
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
