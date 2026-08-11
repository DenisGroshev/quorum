package app.quorum.voting;

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
class VotingControllerIT {

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

    /** Bypasses the real matching algorithm so the deck is a known, predictable set of tmdbIds. */
    private void forceToVoting(UUID sessionId, UUID hostId, long... tmdbIds) {
        for (long id : tmdbIds) {
            insertMovie(id);
        }
        List<ScoredMovie> movies = java.util.Arrays.stream(tmdbIds)
                .mapToObj(id -> new ScoredMovie(id, new BigDecimal("9.0"), List.of()))
                .toList();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Session session = sessionRepository.findById(sessionId).orElseThrow();
            session.startVoting(hostId, new DeckResult(movies, "v1"), "v1", clock);
            sessionRepository.saveAndFlush(session);
        });
    }

    private String createSessionAndJoinMember(TelegramLoginResult hostLogin, TelegramLoginResult memberLogin) {
        ResponseEntity<Map<String, Object>> created =
                exchange(hostLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions", Map.of());
        String sessionId = (String) created.getBody().get("id");
        String inviteCode = (String) created.getBody().get("inviteCode");
        exchange(memberLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions/join", Map.of("inviteCode", inviteCode));
        return sessionId;
    }

    @Test
    void deck_reflectsCastVote_andProgressCountsIt() {
        TelegramLoginResult hostLogin = login(920_001L, "Host", "host920001");
        TelegramLoginResult memberLogin = login(920_002L, "Member", "member920002");
        String sessionId = createSessionAndJoinMember(hostLogin, memberLogin);
        UUID hostId = userIdOf(hostLogin.accessToken());
        forceToVoting(UUID.fromString(sessionId), hostId, 930_001L, 930_002L);

        ResponseEntity<Map<String, Object>> beforeVote =
                exchange(hostLogin.accessToken(), HttpMethod.GET, "/api/v1/sessions/" + sessionId + "/deck", null);
        assertThat(beforeVote.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> items = (List<?>) beforeVote.getBody().get("items");
        assertThat(items).hasSize(2);

        ResponseEntity<Map<String, Object>> voted = exchange(
                hostLogin.accessToken(),
                HttpMethod.PUT,
                "/api/v1/sessions/" + sessionId + "/votes/930001",
                Map.of("value", "LIKE"));
        assertThat(voted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map<String, Object>> afterVote =
                exchange(hostLogin.accessToken(), HttpMethod.GET, "/api/v1/sessions/" + sessionId + "/deck", null);
        Map<String, Object> votedCard = ((List<Map<String, Object>>)
                        afterVote.getBody().get("items"))
                .stream()
                        .filter(c -> ((Number) c.get("tmdbId")).longValue() == 930_001L)
                        .findFirst()
                        .orElseThrow();
        assertThat(votedCard.get("myVote")).isEqualTo("LIKE");

        ResponseEntity<Map<String, Object>> progress =
                exchange(hostLogin.accessToken(), HttpMethod.GET, "/api/v1/sessions/" + sessionId + "/progress", null);
        assertThat(progress.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(progress.getBody().get("totalMembers")).isEqualTo(2);
        assertThat(progress.getBody().get("finishedMembers")).isEqualTo(0);
        assertThat(progress.getBody().get("cardsRemainingForMe")).isEqualTo(1);
    }

    @Test
    void finish_byHost_decidesWinner_andResultShowsIt() {
        TelegramLoginResult hostLogin = login(920_010L, "Host2", "host920010");
        TelegramLoginResult memberLogin = login(920_011L, "Member2", "member920011");
        String sessionId = createSessionAndJoinMember(hostLogin, memberLogin);
        UUID hostId = userIdOf(hostLogin.accessToken());
        forceToVoting(UUID.fromString(sessionId), hostId, 930_010L);
        exchange(
                hostLogin.accessToken(),
                HttpMethod.PUT,
                "/api/v1/sessions/" + sessionId + "/votes/930010",
                Map.of("value", "LIKE"));

        ResponseEntity<Map<String, Object>> finished =
                exchange(hostLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions/" + sessionId + "/finish", null);
        assertThat(finished.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, Object>> result =
                exchange(hostLogin.accessToken(), HttpMethod.GET, "/api/v1/sessions/" + sessionId + "/result", null);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> winner = (Map<String, Object>) result.getBody().get("winner");
        assertThat(((Number) winner.get("tmdbId")).longValue()).isEqualTo(930_010L);
        assertThat(result.getBody().get("contested")).isEqualTo(false);
    }

    @Test
    void finish_byNonHost_returns403() {
        TelegramLoginResult hostLogin = login(920_020L, "Host3", "host920020");
        TelegramLoginResult memberLogin = login(920_021L, "Member3", "member920021");
        String sessionId = createSessionAndJoinMember(hostLogin, memberLogin);
        UUID hostId = userIdOf(hostLogin.accessToken());
        forceToVoting(UUID.fromString(sessionId), hostId, 930_020L);

        ResponseEntity<Map<String, Object>> attempt =
                exchange(memberLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions/" + sessionId + "/finish", null);

        assertThat(attempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(attempt.getBody()).containsKeys("title", "status", "detail");
    }

    @Test
    void finish_whenAlreadyDecided_returns409() {
        TelegramLoginResult hostLogin = login(920_030L, "Host4", "host920030");
        TelegramLoginResult memberLogin = login(920_031L, "Member4", "member920031");
        String sessionId = createSessionAndJoinMember(hostLogin, memberLogin);
        UUID hostId = userIdOf(hostLogin.accessToken());
        forceToVoting(UUID.fromString(sessionId), hostId, 930_030L);
        exchange(
                hostLogin.accessToken(),
                HttpMethod.PUT,
                "/api/v1/sessions/" + sessionId + "/votes/930030",
                Map.of("value", "LIKE"));
        exchange(hostLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions/" + sessionId + "/finish", null);

        ResponseEntity<Map<String, Object>> secondAttempt =
                exchange(hostLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions/" + sessionId + "/finish", null);

        assertThat(secondAttempt.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(secondAttempt.getBody()).containsKeys("title", "status", "detail");
    }

    @Test
    void result_hidesIndividualVotes_butShowsSuperlikesWithVoterNames() {
        TelegramLoginResult hostLogin = login(920_040L, "Anya", "host920040");
        TelegramLoginResult memberLogin = login(920_041L, "Member5", "member920041");
        String sessionId = createSessionAndJoinMember(hostLogin, memberLogin);
        UUID hostId = userIdOf(hostLogin.accessToken());
        forceToVoting(UUID.fromString(sessionId), hostId, 930_040L);
        exchange(
                hostLogin.accessToken(),
                HttpMethod.PUT,
                "/api/v1/sessions/" + sessionId + "/votes/930040",
                Map.of("value", "SUPERLIKE"));
        exchange(
                memberLogin.accessToken(),
                HttpMethod.PUT,
                "/api/v1/sessions/" + sessionId + "/votes/930040",
                Map.of("value", "LIKE"));
        exchange(hostLogin.accessToken(), HttpMethod.POST, "/api/v1/sessions/" + sessionId + "/finish", null);

        ResponseEntity<Map<String, Object>> result =
                exchange(hostLogin.accessToken(), HttpMethod.GET, "/api/v1/sessions/" + sessionId + "/result", null);

        String bodyAsString = result.getBody().toString();
        assertThat(bodyAsString).doesNotContain("member920041").doesNotContain("Member5");
        List<Map<String, Object>> superlikes =
                (List<Map<String, Object>>) result.getBody().get("superlikes");
        assertThat(superlikes).hasSize(1);
        assertThat(superlikes.get(0).get("voterName")).isEqualTo("Anya");
        assertThat(((Number) superlikes.get(0).get("tmdbId")).longValue()).isEqualTo(930_040L);
    }

    @Test
    void castVote_nonParticipant_returns403() {
        TelegramLoginResult hostLogin = login(920_050L, "Host6", "host920050");
        TelegramLoginResult memberLogin = login(920_052L, "Member6", "member920052");
        TelegramLoginResult strangerLogin = login(920_051L, "Stranger6", "stranger920051");
        String sessionId = createSessionAndJoinMember(hostLogin, memberLogin);
        UUID hostId = userIdOf(hostLogin.accessToken());
        forceToVoting(UUID.fromString(sessionId), hostId, 930_050L);

        ResponseEntity<Map<String, Object>> attempt = exchange(
                strangerLogin.accessToken(),
                HttpMethod.PUT,
                "/api/v1/sessions/" + sessionId + "/votes/930050",
                Map.of("value", "LIKE"));

        assertThat(attempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void castVote_movieNotInDeck_returns422NotSql500() {
        TelegramLoginResult hostLogin = login(920_060L, "Host7", "host920060");
        TelegramLoginResult memberLogin = login(920_062L, "Member7", "member920062");
        String sessionId = createSessionAndJoinMember(hostLogin, memberLogin);
        UUID hostId = userIdOf(hostLogin.accessToken());
        forceToVoting(UUID.fromString(sessionId), hostId, 930_060L);
        insertMovie(930_061L);

        ResponseEntity<Map<String, Object>> attempt = exchange(
                hostLogin.accessToken(),
                HttpMethod.PUT,
                "/api/v1/sessions/" + sessionId + "/votes/930061",
                Map.of("value", "LIKE"));

        assertThat(attempt.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(attempt.getBody()).containsKeys("title", "status", "detail");
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
