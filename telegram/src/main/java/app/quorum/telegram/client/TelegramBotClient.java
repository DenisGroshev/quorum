package app.quorum.telegram.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Service
public class TelegramBotClient {

    private static final int TOO_MANY_REQUESTS = 429;

    private final RestClient restClient;

    public TelegramBotClient(
            RestClient.Builder builder,
            @Value("${quorum.telegram.api-base-url:https://api.telegram.org}") String apiBaseUrl,
            @Value("${quorum.telegram.bot-token}") String botToken) {
        // Force HTTP/1.1: the JDK HttpClient's default HTTP/2 upgrade attempt is
        // rejected (RST_STREAM) by some servers used in tests (e.g. WireMock), and
        // the Bot API has no need for HTTP/2.
        this.restClient = builder.baseUrl(apiBaseUrl + "/bot" + botToken)
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
    }

    public TelegramSendResult sendMessage(long chatId, String text) {
        try {
            restClient
                    .post()
                    .uri("/sendMessage")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new SendMessageRequest(chatId, text))
                    .retrieve()
                    .toBodilessEntity();
            return TelegramSendResult.success();
        } catch (HttpClientErrorException e) {
            // 429 is Telegram's rate limit signal - retry later. Every other 4xx (bad
            // chat id, bot blocked/kicked, malformed request, ...) will never succeed
            // on retry.
            if (e.getStatusCode().value() == TOO_MANY_REQUESTS) {
                return TelegramSendResult.transientFailure(e.getMessage());
            }
            return TelegramSendResult.permanentFailure(e.getMessage());
        } catch (HttpServerErrorException e) {
            return TelegramSendResult.transientFailure(e.getMessage());
        } catch (ResourceAccessException e) {
            return TelegramSendResult.transientFailure(e.getMessage());
        }
    }

    private record SendMessageRequest(@JsonProperty("chat_id") long chatId, String text) {}
}
