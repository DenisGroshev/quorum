package app.quorum.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.user.api.TelegramLoginResult;
import app.quorum.watchlist.domain.UserMovieKey;
import app.quorum.watchlist.infra.WatchlistItemRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WatchlistControllerIT {

    private static final String BOT_TOKEN = "it-test-bot-token";

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final WireMockServer TMDB =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        TMDB.start();
    }

    @AfterAll
    static void stopWireMock() {
        TMDB.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("TG_BOT_TOKEN", () -> BOT_TOKEN);
        registry.add("JWT_SECRET", () -> "integration-test-jwt-secret-should-be-long-enough");
        registry.add("TG_APP_NAME", () -> "quorum-it-tests");
        registry.add("TMDB_TOKEN", () -> "unused-in-this-test");
        registry.add("TMDB_BASE_URL", TMDB::baseUrl);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private WatchlistItemRepository watchlistItemRepository;

    @Autowired
    private JdbcClient jdbcClient;

    private void insertFreshMovie(long tmdbId) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO movies (tmdb_id, original_title, vote_avg, vote_count, synced_at)
                        VALUES (:tmdbId, :title, :voteAvg, :voteCount, :syncedAt)
                        """)
                .param("tmdbId", tmdbId)
                .param("title", "Movie " + tmdbId)
                .param("voteAvg", new BigDecimal("7.5"))
                .param("voteCount", 100)
                .param("syncedAt", Timestamp.from(Instant.now()))
                .update();
    }

    @Test
    void allSevenEndpoints_withoutAuthorizationHeader_return401() {
        long tmdbId = 890_001L;

        assertUnauthorized(exchange(null, HttpMethod.GET, "/api/v1/watchlist", null));
        assertUnauthorized(exchange(null, HttpMethod.PUT, "/api/v1/watchlist/" + tmdbId, null));
        assertUnauthorized(exchange(null, HttpMethod.DELETE, "/api/v1/watchlist/" + tmdbId, null));
        assertUnauthorized(exchange(null, HttpMethod.GET, "/api/v1/seen", null));
        assertUnauthorized(exchange(null, HttpMethod.PUT, "/api/v1/seen/" + tmdbId, null));
        assertUnauthorized(exchange(null, HttpMethod.DELETE, "/api/v1/seen/" + tmdbId, null));
        assertUnauthorized(exchange(null, HttpMethod.POST, "/api/v1/movies/flags", List.of(tmdbId)));
    }

    @Test
    void allSevenEndpoints_withValidToken_respondSuccessfully() {
        TelegramLoginResult login = login(890_100L, "Seven", "seven");
        long tmdbId = 890_101L;
        insertFreshMovie(tmdbId);

        assertOk(exchange(login.accessToken(), HttpMethod.PUT, "/api/v1/watchlist/" + tmdbId, null));
        assertOk(exchange(login.accessToken(), HttpMethod.GET, "/api/v1/watchlist", null));
        assertOk(exchange(login.accessToken(), HttpMethod.PUT, "/api/v1/seen/" + tmdbId, null));
        assertOk(exchange(login.accessToken(), HttpMethod.GET, "/api/v1/seen", null));
        assertNoContent(exchange(login.accessToken(), HttpMethod.DELETE, "/api/v1/seen/" + tmdbId, null));
        assertNoContent(exchange(login.accessToken(), HttpMethod.DELETE, "/api/v1/watchlist/" + tmdbId, null));
        assertOk(exchange(login.accessToken(), HttpMethod.POST, "/api/v1/movies/flags", List.of(tmdbId)));
    }

    @Test
    void addToWatchlist_attemptingToPassAnotherUsersIdAsQueryParam_stillActsOnlyOnTheAuthenticatedUser() {
        TelegramLoginResult owner = login(890_200L, "Owner", "owner");
        TelegramLoginResult attacker = login(890_201L, "Attacker", "attacker");
        UUID ownerId = userIdOf(owner.accessToken());
        UUID attackerId = userIdOf(attacker.accessToken());
        long tmdbId = 890_202L;
        insertFreshMovie(tmdbId);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/watchlist/" + tmdbId + "?userId=" + ownerId,
                HttpMethod.PUT,
                authorizedEntity(attacker.accessToken(), null),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(watchlistItemRepository.existsById(new UserMovieKey(attackerId, tmdbId)))
                .isTrue();
        assertThat(watchlistItemRepository.existsById(new UserMovieKey(ownerId, tmdbId)))
                .isFalse();
    }

    @Test
    void addToWatchlist_responseReflectsCurrentStateNotJustSuccess() {
        TelegramLoginResult login = login(890_300L, "State", "state");
        long tmdbId = 890_301L;
        insertFreshMovie(tmdbId);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/watchlist/" + tmdbId,
                HttpMethod.PUT,
                authorizedEntity(login.accessToken(), null),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("inWatchlist")).isEqualTo(true);
        assertThat(body.get("seen")).isEqualTo(false);
        assertThat(body.get("watchlistCount")).isEqualTo(1);
        assertThat(body.get("seenCount")).isEqualTo(0);
    }

    @Test
    void addToWatchlist_movieDoesNotExistAnywhere_returns404NotFound() {
        TelegramLoginResult login = login(890_400L, "Ghost", "ghost");
        long tmdbId = 890_401L;
        TMDB.stubFor(
                get(urlPathEqualTo("/movie/" + tmdbId)).willReturn(aResponse().withStatus(404)));

        ResponseEntity<Map<String, Object>> response =
                exchange(login.accessToken(), HttpMethod.PUT, "/api/v1/watchlist/" + tmdbId, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private void assertUnauthorized(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private void assertOk(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void assertNoContent(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private ResponseEntity<Map<String, Object>> exchange(
            String accessToken, HttpMethod method, String path, Object body) {
        return restTemplate.exchange(
                path,
                method,
                authorizedEntity(accessToken, body),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private HttpEntity<Object> authorizedEntity(String accessToken, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (accessToken != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
        return new HttpEntity<>(body, headers);
    }

    private UUID userIdOf(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.GET,
                new HttpEntity<Void>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        Map<String, Object> body = response.getBody();
        return body != null ? UUID.fromString((String) body.get("id")) : null;
    }

    private TelegramLoginResult login(long tgUserId, String firstName, String username) {
        String userJson =
                "{\"id\":" + tgUserId + ",\"first_name\":\"" + firstName + "\",\"username\":\"" + username + "\"}";
        String initData =
                sign(Map.of("auth_date", String.valueOf(Instant.now().getEpochSecond()), "user", userJson), BOT_TOKEN);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "tma " + initData);

        ResponseEntity<TelegramLoginResult> response = restTemplate.exchange(
                "/api/v1/auth/telegram", HttpMethod.POST, new HttpEntity<Void>(headers), TelegramLoginResult.class);
        return response.getBody();
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
