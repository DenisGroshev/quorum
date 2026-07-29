package app.quorum.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.quorum.auth.api.TelegramUser;
import app.quorum.common.exception.InvalidDataInitException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class RequestingUserServiceTest {

    private static final String BOT_TOKEN = "test-bot-token";
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration MAX_AGE = Duration.ofSeconds(300);

    private final RequestingUserService service =
            new RequestingUserService(CLOCK, new ObjectMapper(), MAX_AGE, BOT_TOKEN);

    @Test
    void authenticate_validInitData_returnsParsedUser() {
        String initData = sign(
                Map.of(
                        "auth_date",
                        String.valueOf(NOW.getEpochSecond()),
                        "user",
                        "{\"id\":422807590,\"first_name\":\"Denis\",\"last_name\":\"K\","
                                + "\"username\":\"sansregrett\",\"photo_url\":\"https://t.me/a.jpg\","
                                + "\"language_code\":\"ru\"}"),
                BOT_TOKEN);

        TelegramUser user = service.authenticate(initData);

        assertThat(user.id()).isEqualTo(422807590L);
        assertThat(user.firstName()).isEqualTo("Denis");
        assertThat(user.lastName()).isEqualTo("K");
        assertThat(user.userName()).isEqualTo("sansregrett");
        assertThat(user.photoUrl()).isEqualTo("https://t.me/a.jpg");
        assertThat(user.languageCode()).isEqualTo("ru");
    }

    @Test
    void authenticate_signedWithWrongBotToken_throws() {
        String initData = sign(
                Map.of("auth_date", String.valueOf(NOW.getEpochSecond()), "user", "{\"id\":1,\"first_name\":\"A\"}"),
                "another-bot-token");

        assertThrows(InvalidDataInitException.class, () -> service.authenticate(initData));
    }

    @Test
    void authenticate_expiredAuthDate_throws() {
        Instant expired = NOW.minus(MAX_AGE).minusSeconds(1);
        String initData = sign(
                Map.of(
                        "auth_date",
                        String.valueOf(expired.getEpochSecond()),
                        "user",
                        "{\"id\":1,\"first_name\":\"A\"}"),
                BOT_TOKEN);

        assertThrows(InvalidDataInitException.class, () -> service.authenticate(initData));
    }

    @Test
    void authenticate_authDateInFuture_throws() {
        Instant future = NOW.plusSeconds(60);
        String initData = sign(
                Map.of("auth_date", String.valueOf(future.getEpochSecond()), "user", "{\"id\":1,\"first_name\":\"A\"}"),
                BOT_TOKEN);

        assertThrows(InvalidDataInitException.class, () -> service.authenticate(initData));
    }

    @Test
    void authenticate_missingHash_throws() {
        String withoutHash = "auth_date=" + NOW.getEpochSecond() + "&user=%7B%22id%22%3A1%7D";

        assertThrows(InvalidDataInitException.class, () -> service.authenticate(withoutHash));
    }

    @Test
    void authenticate_malformedHashHex_throws() {
        String initData = sign(
                Map.of("auth_date", String.valueOf(NOW.getEpochSecond()), "user", "{\"id\":1,\"first_name\":\"A\"}"),
                BOT_TOKEN);
        String tampered = initData.replaceAll("hash=[0-9a-f]+$", "hash=not-hex-zzz");

        assertThrows(InvalidDataInitException.class, () -> service.authenticate(tampered));
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
