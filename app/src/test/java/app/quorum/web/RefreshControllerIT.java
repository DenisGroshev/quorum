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

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RefreshControllerIT {

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
    void refresh_validRefreshTokenFromLogin_issuesNewPairAndOldTokenBecomesUnusable() {
        TelegramLoginResult login = login(701L);

        ResponseEntity<TelegramLoginResult> firstRefresh = postRefresh(login.refreshToken());
        assertThat(firstRefresh.getStatusCode()).isEqualTo(HttpStatus.OK);
        TelegramLoginResult rotated = firstRefresh.getBody();
        assertThat(rotated).isNotNull();
        assertThat(rotated.refreshToken()).isNotEqualTo(login.refreshToken());
        assertThat(rotated.accessToken()).isNotBlank();

        ResponseEntity<Map<String, Object>> reuseAttempt = postRefreshRaw(login.refreshToken());
        assertThat(reuseAttempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refresh_reusedRevokedToken_returns401WithReusedCodeAndOldAccessTokenStopsWorking() {
        TelegramLoginResult login = login(702L);

        // consume the refresh token once (legitimate rotation)
        postRefresh(login.refreshToken());

        // now replay the ORIGINAL (already-revoked) refresh token: this is the attack scenario
        ResponseEntity<Map<String, Object>> reuse = postRefreshRaw(login.refreshToken());

        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        Map<String, Object> body = reuse.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code")).isEqualTo("auth.refresh.reused");

        // the very first access token issued at login must now be dead too
        ResponseEntity<Map<String, Object>> me = getMe(login.accessToken());
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refresh_unknownToken_returns401() {
        ResponseEntity<Map<String, Object>> response = postRefreshRaw("not-a-real-refresh-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private TelegramLoginResult login(long tgUserId) {
        String userJson = "{\"id\":" + tgUserId + ",\"first_name\":\"Racer\",\"username\":\"racer\"}";
        String initData =
                sign(Map.of("auth_date", String.valueOf(Instant.now().getEpochSecond()), "user", userJson), BOT_TOKEN);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "tma " + initData);

        ResponseEntity<TelegramLoginResult> response = restTemplate.exchange(
                "/api/v1/auth/telegram", HttpMethod.POST, new HttpEntity<Void>(headers), TelegramLoginResult.class);
        return response.getBody();
    }

    private ResponseEntity<TelegramLoginResult> postRefresh(String refreshToken) {
        return restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshTokenRequest(refreshToken), TelegramLoginResult.class);
    }

    private ResponseEntity<Map<String, Object>> postRefreshRaw(String refreshToken) {
        HttpEntity<RefreshTokenRequest> entity = new HttpEntity<>(new RefreshTokenRequest(refreshToken));
        return restTemplate.exchange(
                "/api/v1/auth/refresh",
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private ResponseEntity<Map<String, Object>> getMe(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);

        return restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.GET,
                new HttpEntity<Void>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {});
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
