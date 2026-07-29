package app.quorum.auth.service;

import app.quorum.auth.api.TelegramUser;
import app.quorum.auth.domain.QueryParam;
import app.quorum.common.exception.InvalidDataInitException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RequestingUserService {

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final Duration initDataMaxAge;
    private final String botToken;

    public RequestingUserService(
            Clock clock,
            ObjectMapper objectMapper,
            @Value("${quorum.auth.init-data-max-age}") Duration initDataMaxAge,
            @Value("${quorum.telegram.bot-token}") String botToken) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.initDataMaxAge = initDataMaxAge;
        this.botToken = botToken;
    }

    public TelegramUser authenticate(String rawInitData) {
        List<QueryParam> params = parseQueryString(rawInitData);

        String receivedHash = findRequired(params, "hash");
        String dataCheckString = buildDataCheckString(params);

        byte[] secretKey = createSecretKey();
        byte[] computedHash = calculateHash(secretKey, dataCheckString);

        verifyHash(receivedHash, computedHash);
        verifyAuthDate(findRequired(params, "auth_date"));

        String userJson = findRequired(params, "user");
        return parseUser(userJson);
    }

    private List<QueryParam> parseQueryString(String initData) {
        if (initData == null || initData.isBlank()) {
            throw new InvalidDataInitException("initial data is empty");
        }

        return Arrays.stream(initData.split("&"))
                .map(pair -> {
                    String[] parts = pair.split("=", 2);

                    if (parts.length != 2) {
                        throw new InvalidDataInitException("initial data contains invalid parameter: " + pair);
                    }

                    String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);

                    String value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                    return new QueryParam(key, value);
                })
                .toList();
    }

    private String findRequired(List<QueryParam> params, String requiredKey) {
        List<String> values = params.stream()
                .filter(param -> param.key().equals(requiredKey))
                .map(QueryParam::value)
                .toList();

        if (values.isEmpty()) {
            throw new InvalidDataInitException("required param is missing: " + requiredKey);
        }
        if (values.size() > 1) {
            throw new InvalidDataInitException("duplicate param: " + requiredKey);
        }

        return values.getFirst();
    }

    private String buildDataCheckString(List<QueryParam> params) {
        return params.stream()
                .filter(param -> !param.key().equals("hash"))
                .sorted(Comparator.comparing(QueryParam::key))
                .map(param -> param.key() + "=" + param.value())
                .collect(Collectors.joining("\n"));
    }

    private byte[] createSecretKey() {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec key = new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(key);

            return mac.doFinal(botToken.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot calculate Telegram secret key", e);
        }
    }

    private byte[] calculateHash(byte[] secretKey, String dataCheckString) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");

            mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));

            return mac.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot calculate Telegram initData hash", exception);
        }
    }

    private void verifyHash(String receivedHash, byte[] computedHash) {
        final byte[] receivedHashBytes;

        try {
            receivedHashBytes = HexFormat.of().parseHex(receivedHash);
        } catch (IllegalArgumentException exception) {
            throw new InvalidDataInitException("Hash has invalid hexadecimal format");
        }

        if (!MessageDigest.isEqual(receivedHashBytes, computedHash)) {
            throw new InvalidDataInitException("Telegram initData hash is invalid");
        }
    }

    private void verifyAuthDate(String rawAuthDate) {
        final long authDateSeconds;

        try {
            authDateSeconds = Long.parseLong(rawAuthDate);
        } catch (NumberFormatException exception) {
            throw new InvalidDataInitException("auth_date is not a valid Unix timestamp");
        }

        Instant authDate = Instant.ofEpochSecond(authDateSeconds);
        Instant now = Instant.now(clock);

        Duration age = Duration.between(authDate, now);

        if (age.isNegative()) {
            throw new InvalidDataInitException("auth_date is in the future");
        }

        if (age.compareTo(initDataMaxAge) > 0) {
            throw new InvalidDataInitException("Telegram initData has expired");
        }
    }

    private TelegramUser parseUser(String userJson) {
        try {
            return objectMapper.readValue(userJson, TelegramUser.class);
        } catch (JsonProcessingException exception) {
            throw new InvalidDataInitException("Telegram user JSON is invalid", exception);
        }
    }
}
