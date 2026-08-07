package app.quorum.web;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.common.ContentLang;
import app.quorum.user.api.TelegramLoginResult;
import app.quorum.user.domain.Genre;
import app.quorum.user.domain.User;
import app.quorum.user.repository.GenreRepository;
import app.quorum.user.repository.UserRepository;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserControllerIT {

    private static final String BOT_TOKEN = "it-test-bot-token";
    private static final String ME_PATH = "/api/v1/users/me";

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

    @Autowired
    private GenreRepository genreRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void me_withValidAccessTokenFromLogin_returnsProfile() {
        TelegramLoginResult login = login(444L, "Carol", "carol");

        ResponseEntity<Map<String, Object>> response = getMe(login.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("displayName", "Carol");
        assertThat(response.getBody()).containsEntry("username", "carol");
        assertThat(response.getBody()).containsEntry("watchlistCount", 0);
        assertThat(response.getBody()).containsEntry("movieNightsCount", 0);
    }

    @Test
    void me_afterAddingMovieToWatchlist_watchlistCountIncreases() {
        TelegramLoginResult login = login(920_001L, "Mia", "mia");
        long tmdbId = 920_002L;
        insertFreshMovie(tmdbId);

        ResponseEntity<Map<String, Object>> before = getMe(login.accessToken());
        addToWatchlist(login.accessToken(), tmdbId);
        ResponseEntity<Map<String, Object>> after = getMe(login.accessToken());

        assertThat(before.getBody()).containsEntry("watchlistCount", 0);
        assertThat(after.getBody()).containsEntry("watchlistCount", 1);
    }

    @Test
    void me_withoutAuthorizationHeader_returns401ProblemDetail() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                ME_PATH,
                HttpMethod.GET,
                new HttpEntity<Void>(new HttpHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKeys("type", "title", "status", "detail");
        assertThat(body.get("status")).isEqualTo(401);
    }

    @Test
    void me_withMalformedAccessToken_returns401() {
        ResponseEntity<Map<String, Object>> response = getMe("this.is.not-a-valid-jwt");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void me_afterTokenVersionBumped_oldAccessTokenNoLongerWorks() {
        TelegramLoginResult login = login(555L, "Dave", "dave");

        User user = userRepository.findByTgUserId(555L).orElseThrow();
        user.bumpTokenVersion();
        userRepository.save(user);

        ResponseEntity<Map<String, Object>> response = getMe(login.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void replaceFavoriteGenres_tenExistingGenres_succeeds() {
        TelegramLoginResult login = login(801L, "Gina", "gina");
        Set<Integer> genreIds = Set.copyOf(seedGenres(10, 100));

        ResponseEntity<Void> response = putFavoriteGenres(login.accessToken(), genreIds);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void replaceFavoriteGenres_elevenGenres_returns422() {
        TelegramLoginResult login = login(802L, "Hank", "hank");
        Set<Integer> genreIds = Set.copyOf(seedGenres(11, 200));

        ResponseEntity<Map<String, Object>> response = putFavoriteGenresRaw(login.accessToken(), genreIds);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void replaceFavoriteGenres_nonexistentGenre_returns422() {
        TelegramLoginResult login = login(803L, "Ivy", "ivy");

        ResponseEntity<Map<String, Object>> response = putFavoriteGenresRaw(login.accessToken(), Set.of(999_999));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void updateSettings_partialUpdate_doesNotClobberOtherField() {
        TelegramLoginResult login = login(804L, "Jack", "jack");

        patchSettings(login.accessToken(), new UpdateSettingsRequest(false, "en"));
        ResponseEntity<Void> response = patchSettings(login.accessToken(), new UpdateSettingsRequest(true, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        User user = userRepository.findByTgUserId(804L).orElseThrow();
        assertThat(user.isNotifyEnabled()).isTrue();
        assertThat(user.getLangContent()).isEqualTo(ContentLang.EN);
    }

    @Test
    void deleteAccount_anonymizesProfileAndInvalidatesOldAccessTokenAndRefreshToken() {
        TelegramLoginResult login = login(901L, "Karl", "karl");
        UUID userId = userIdOf(login.accessToken());

        ResponseEntity<Void> deleteResponse = deleteAccount(login.accessToken());
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        User anonymized = userRepository.findById(userId).orElseThrow();
        assertThat(anonymized.getTgUserId()).isNull();
        assertThat(anonymized.getUsername()).isNull();
        assertThat(anonymized.getDisplayName()).isEqualTo("Удалённый пользователь");
        assertThat(anonymized.getDeletedAt()).isNotNull();

        ResponseEntity<Map<String, Object>> meAfterDelete = getMe(login.accessToken());
        assertThat(meAfterDelete.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginAfterDeletion_createsBrandNewAccount() {
        TelegramLoginResult firstLogin = login(902L, "Leo", "leo");
        UUID firstAccountId = userIdOf(firstLogin.accessToken());

        ResponseEntity<Void> deleteResponse = deleteAccount(firstLogin.accessToken());
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        TelegramLoginResult secondLogin = login(902L, "Leo", "leo");
        UUID secondAccountId = userIdOf(secondLogin.accessToken());

        assertThat(secondAccountId).isNotEqualTo(firstAccountId);
        User recreated = userRepository.findByTgUserId(902L).orElseThrow();
        assertThat(recreated.getDisplayName()).isEqualTo("Leo");
        assertThat(recreated.getDeletedAt()).isNull();
    }

    private UUID userIdOf(String accessToken) {
        Map<String, Object> body = getMe(accessToken).getBody();
        return body != null ? UUID.fromString((String) body.get("id")) : null;
    }

    private ResponseEntity<Void> deleteAccount(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);

        return restTemplate.exchange(ME_PATH, HttpMethod.DELETE, new HttpEntity<Void>(headers), Void.class);
    }

    private ResponseEntity<Void> addToWatchlist(String accessToken, long tmdbId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);

        return restTemplate.exchange(
                "/api/v1/watchlist/" + tmdbId, HttpMethod.PUT, new HttpEntity<Void>(headers), Void.class);
    }

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

    private List<Integer> seedGenres(int count, int startId) {
        List<Genre> genres = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        IntStream.range(0, count).forEach(i -> {
            int id = startId + i;
            genres.add(Genre.of(id, "genre-ru-" + id, "genre-en-" + id));
            ids.add(id);
        });
        genreRepository.saveAll(genres);
        return ids;
    }

    private ResponseEntity<Void> putFavoriteGenres(String accessToken, Set<Integer> genreIds) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        HttpEntity<ReplaceFavoriteGenresRequest> entity =
                new HttpEntity<>(new ReplaceFavoriteGenresRequest(genreIds), headers);

        return restTemplate.exchange("/api/v1/users/me/favorite-genres", HttpMethod.PUT, entity, Void.class);
    }

    private ResponseEntity<Map<String, Object>> putFavoriteGenresRaw(String accessToken, Set<Integer> genreIds) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        HttpEntity<ReplaceFavoriteGenresRequest> entity =
                new HttpEntity<>(new ReplaceFavoriteGenresRequest(genreIds), headers);

        return restTemplate.exchange(
                "/api/v1/users/me/favorite-genres",
                HttpMethod.PUT,
                entity,
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private ResponseEntity<Void> patchSettings(String accessToken, UpdateSettingsRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        HttpEntity<UpdateSettingsRequest> entity = new HttpEntity<>(request, headers);

        return restTemplate.exchange("/api/v1/users/me/settings", HttpMethod.PATCH, entity, Void.class);
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

    private ResponseEntity<Map<String, Object>> getMe(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);

        return restTemplate.exchange(
                ME_PATH,
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
