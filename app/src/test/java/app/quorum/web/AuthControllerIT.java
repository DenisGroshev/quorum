package app.quorum.web;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
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
class AuthControllerIT {

    private static final String BOT_TOKEN = "it-test-bot-token";
    private static final String LOGIN_PATH = "/api/v1/auth/telegram";

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

    @Autowired
    private UserRepository userRepository;

    @Test
    void telegram_validInitData_issuesTokensAndPersistsUser() {
        ResponseEntity<Map<String, Object>> response =
                callTelegramLogin(telegramUserJson(111L, "Alice", "Smith", "alice"), Instant.now());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKeys("accessToken", "refreshToken", "expiresInSeconds");
        assertThat((String) body.get("accessToken")).isNotBlank();
        assertThat((String) body.get("refreshToken")).isNotBlank();

        User saved = userRepository.findByTgUserId(111L).orElseThrow();
        assertThat(saved.getDisplayName()).isEqualTo("Alice Smith");
        assertThat(saved.getUsername()).isEqualTo("alice");
    }

    @Test
    void telegram_sameTelegramUserTwice_updatesExistingRow() {
        callTelegramLogin(telegramUserJson(222L, "Bob", "Old", "bob_old"), Instant.now());
        callTelegramLogin(telegramUserJson(222L, "Bob", "New", "bob_new"), Instant.now());

        assertThat(userRepository.findByTgUserId(222L)).isPresent();
        User saved = userRepository.findByTgUserId(222L).orElseThrow();
        assertThat(saved.getDisplayName()).isEqualTo("Bob New");
        assertThat(saved.getUsername()).isEqualTo("bob_new");
    }

    @Test
    void telegram_hashSignedWithWrongBotToken_returns401() {
        String initData = sign(
                Map.of(
                        "auth_date", String.valueOf(Instant.now().getEpochSecond()),
                        "user", telegramUserJson(333L, "Eve", "X", "eve")),
                "not-the-configured-bot-token");

        ResponseEntity<Map<String, Object>> response = postLogin("tma " + initData);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void telegram_authorizationWithoutTmaScheme_returns401() {
        ResponseEntity<Map<String, Object>> response = postLogin("Bearer something");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<Map<String, Object>> callTelegramLogin(String userJson, Instant authDate) {
        String initData =
                sign(Map.of("auth_date", String.valueOf(authDate.getEpochSecond()), "user", userJson), BOT_TOKEN);
        return postLogin("tma " + initData);
    }

    private ResponseEntity<Map<String, Object>> postLogin(String authorizationHeaderValue) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, authorizationHeaderValue);

        return restTemplate.exchange(
                LOGIN_PATH,
                HttpMethod.POST,
                new HttpEntity<Void>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private static String telegramUserJson(long id, String firstName, String lastName, String username) {
        return "{\"id\":" + id + ",\"first_name\":\"" + firstName + "\",\"last_name\":\"" + lastName
                + "\",\"username\":\"" + username + "\"}";
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
