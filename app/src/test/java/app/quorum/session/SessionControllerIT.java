package app.quorum.session;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.session.domain.DeckResult;
import app.quorum.session.domain.ScoredMovie;
import app.quorum.session.domain.Session;
import app.quorum.session.infra.SessionRepository;
import app.quorum.user.api.TelegramLoginResult;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionControllerIT {

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

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private void insertMovie(long tmdbId) {
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

    private void forceToDecided(UUID sessionId, UUID hostId, UUID memberId, long winnerTmdbId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Session session = sessionRepository.findById(sessionId).orElseThrow();
            session.startVoting(
                    hostId,
                    new DeckResult(List.of(new ScoredMovie(winnerTmdbId, new BigDecimal("9.0"), List.of())), "v1"),
                    "v1",
                    clock);
            session.decide(winnerTmdbId, false, clock);
            sessionRepository.saveAndFlush(session);
        });
    }

    @Test
    void create_join_view_list_patch_start_cancel_allRespondSuccessfully() {
        TelegramLoginResult hostLogin = login(910_001L, "Host", "host");
        TelegramLoginResult memberLogin = login(910_002L, "Member", "member");

        ResponseEntity<Map<String, Object>> created =
                exchange(hostLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions", Map.of("title", "Test night"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        String inviteCode = (String) created.getBody().get("inviteCode");
        String sessionId = (String) created.getBody().get("id");

        ResponseEntity<Map<String, Object>> joined = exchange(
                memberLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions/join", Map.of("inviteCode", inviteCode));
        assertThat(joined.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(joined.getBody().get("id")).isEqualTo(sessionId);

        ResponseEntity<Map<String, Object>> viewed =
                exchange(hostLogin.accessToken(), HttpMethod.GET, "/api/v1/sessions/" + sessionId, null);
        assertThat(viewed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) viewed.getBody().get("members")).hasSize(2);

        ResponseEntity<Map<String, Object>> listed =
                exchange(hostLogin.accessToken(), HttpMethod.GET, "/api/v1/sessions", null);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) listed.getBody().get("active")).hasSize(1);

        ResponseEntity<Map<String, Object>> patched = exchange(
                hostLogin.accessToken(),
                HttpMethod.PATCH,
                "/api/v1/sessions/" + sessionId,
                Map.of("title", "Renamed night"));
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map<String, Object>> started =
                exchange(hostLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions/" + sessionId + "/start", null);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, Object>> cancelled =
                exchange(hostLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions/" + sessionId + "/cancel", null);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void watchedAndRate_respondSuccessfully_onceSessionIsDecided() {
        TelegramLoginResult hostLogin = login(910_010L, "Host2", "host2");
        TelegramLoginResult memberLogin = login(910_011L, "Member2", "member2");
        insertMovie(910_100L);

        ResponseEntity<Map<String, Object>> created =
                exchange(hostLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions", Map.of());
        String sessionId = (String) created.getBody().get("id");
        String inviteCode = (String) created.getBody().get("inviteCode");
        exchange(memberLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions/join", Map.of("inviteCode", inviteCode));

        UUID hostId = userIdOf(hostLogin.accessToken());
        UUID memberId = userIdOf(memberLogin.accessToken());
        forceToDecided(UUID.fromString(sessionId), hostId, memberId, 910_100L);

        ResponseEntity<Map<String, Object>> watched =
                exchange(hostLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions/" + sessionId + "/watched", null);
        assertThat(watched.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, Object>> rated = exchange(
                memberLogin.accessToken(),
                HttpMethod.PUT,
                "/api/v1/sessions/" + sessionId + "/my-rating",
                Map.of("value", 5));
        assertThat(rated.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void kick_respondsWithNoContent() {
        TelegramLoginResult hostLogin = login(910_020L, "Host3", "host3");
        TelegramLoginResult memberLogin = login(910_021L, "Member3", "member3");

        ResponseEntity<Map<String, Object>> created =
                exchange(hostLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions", Map.of());
        String sessionId = (String) created.getBody().get("id");
        String inviteCode = (String) created.getBody().get("inviteCode");
        exchange(memberLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions/join", Map.of("inviteCode", inviteCode));
        UUID memberId = userIdOf(memberLogin.accessToken());

        ResponseEntity<Map<String, Object>> kicked = exchange(
                hostLogin.accessToken(),
                HttpMethod.DELETE,
                "/api/v1/sessions/" + sessionId + "/members/" + memberId,
                null);

        assertThat(kicked.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void nonParticipant_getsNotFoundWithProblemDetailShape_onViewAndOnHostAction() {
        TelegramLoginResult hostLogin = login(910_030L, "Host4", "host4");
        TelegramLoginResult strangerLogin = login(910_031L, "Stranger", "stranger");

        ResponseEntity<Map<String, Object>> created =
                exchange(hostLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions", Map.of());
        String sessionId = (String) created.getBody().get("id");

        ResponseEntity<Map<String, Object>> viewAttempt =
                exchange(strangerLogin.accessToken(), HttpMethod.GET, "/api/v1/sessions/" + sessionId, null);
        assertThat(viewAttempt.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(viewAttempt.getBody()).containsKeys("title", "status", "detail");
        assertThat(viewAttempt.getBody().get("status")).isEqualTo(404);

        ResponseEntity<Map<String, Object>> startAttempt = exchange(
                strangerLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions/" + sessionId + "/start", null);
        assertThat(startAttempt.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void nonHostParticipant_getsForbiddenWithProblemDetailShape_onHostAction() {
        TelegramLoginResult hostLogin = login(910_040L, "Host5", "host5");
        TelegramLoginResult memberLogin = login(910_041L, "Member5", "member5");

        ResponseEntity<Map<String, Object>> created =
                exchange(hostLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions", Map.of());
        String sessionId = (String) created.getBody().get("id");
        String inviteCode = (String) created.getBody().get("inviteCode");
        exchange(memberLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions/join", Map.of("inviteCode", inviteCode));

        ResponseEntity<Map<String, Object>> startAttempt =
                exchange(memberLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions/" + sessionId + "/start", null);

        assertThat(startAttempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(startAttempt.getBody()).containsKeys("title", "status", "detail");
        assertThat(startAttempt.getBody().get("status")).isEqualTo(403);
    }

    /**
     * Step 9 checklist: "Не-хост получает 403 на каждом" (on EACH host action), called out by name
     * for /start above already - this covers the rest: PATCH, cancel, watched, kick (DELETE member).
     * /my-rating is deliberately excluded - per the endpoint table it's participant-scoped, not
     * host-scoped, so a non-host member is expected to succeed there, not get 403.
     */
    @Test
    void allRemainingHostOnlyEndpoints_nonHostParticipant_return403() {
        TelegramLoginResult hostLogin = login(910_050L, "Host6", "host6");
        TelegramLoginResult memberLogin = login(910_051L, "Member6", "member6");
        String sessionId = createSessionAndJoinMember(hostLogin, memberLogin);
        UUID memberId = userIdOf(memberLogin.accessToken());

        assertForbidden(exchange(
                memberLogin.accessToken(), HttpMethod.PATCH, "/api/v1/sessions/" + sessionId, Map.of("title", "x")));
        assertForbidden(exchange(
                memberLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions/" + sessionId + "/cancel", null));
        assertForbidden(exchange(
                memberLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions/" + sessionId + "/watched", null));
        assertForbidden(exchange(
                memberLogin.accessToken(),
                HttpMethod.DELETE,
                "/api/v1/sessions/" + sessionId + "/members/" + memberId,
                null));
    }

    @Test
    void allRemainingParticipantOnlyEndpoints_nonParticipant_return404() {
        TelegramLoginResult hostLogin = login(910_060L, "Host7", "host7");
        TelegramLoginResult strangerLogin = login(910_061L, "Stranger7", "stranger7");

        ResponseEntity<Map<String, Object>> created =
                exchange(hostLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions", Map.of());
        String sessionId = (String) created.getBody().get("id");
        UUID strangerId = userIdOf(strangerLogin.accessToken());

        assertNotFound(exchange(
                strangerLogin.accessToken(), HttpMethod.PATCH, "/api/v1/sessions/" + sessionId, Map.of("title", "x")));
        assertNotFound(exchange(
                strangerLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions/" + sessionId + "/cancel", null));
        assertNotFound(exchange(
                strangerLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions/" + sessionId + "/watched", null));
        assertNotFound(exchange(
                strangerLogin.accessToken(),
                HttpMethod.DELETE,
                "/api/v1/sessions/" + sessionId + "/members/" + strangerId,
                null));
        assertNotFound(exchange(
                strangerLogin.accessToken(),
                HttpMethod.PUT,
                "/api/v1/sessions/" + sessionId + "/my-rating",
                Map.of("value", 3)));
    }

    private String createSessionAndJoinMember(TelegramLoginResult hostLogin, TelegramLoginResult memberLogin) {
        ResponseEntity<Map<String, Object>> created =
                exchange(hostLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions", Map.of());
        String sessionId = (String) created.getBody().get("id");
        String inviteCode = (String) created.getBody().get("inviteCode");
        exchange(memberLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions/join", Map.of("inviteCode", inviteCode));
        return sessionId;
    }

    private void assertForbidden(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsKeys("title", "status", "detail");
        assertThat(response.getBody().get("status")).isEqualTo(403);
    }

    private void assertNotFound(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsKeys("title", "status", "detail");
        assertThat(response.getBody().get("status")).isEqualTo(404);
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
