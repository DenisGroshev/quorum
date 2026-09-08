package app.quorum.telegram.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
    private final ObjectMapper objectMapper;

    public TelegramBotClient(
            RestClient.Builder builder,
            @Value("${quorum.telegram.api-base-url:https://api.telegram.org}") String apiBaseUrl,
            @Value("${quorum.telegram.bot-token}") String botToken,
            ObjectMapper objectMapper) {
        this.restClient = builder.baseUrl(apiBaseUrl + "/bot" + botToken)
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        this.objectMapper = objectMapper;
    }

    public TelegramSendResult sendMessage(long chatId, String text) {
        return sendMessage(chatId, text, null);
    }

    public TelegramSendResult sendMessage(long chatId, String text, String buttonText, String buttonUrl) {
        return sendMessage(
                chatId,
                text,
                new InlineKeyboardMarkup(List.of(List.of(new InlineKeyboardButton(buttonText, buttonUrl)))));
    }

    private TelegramSendResult sendMessage(long chatId, String text, InlineKeyboardMarkup replyMarkup) {
        try {
            restClient
                    .post()
                    .uri("/sendMessage")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new SendMessageRequest(chatId, text, "HTML", replyMarkup))
                    .retrieve()
                    .toBodilessEntity();
            return TelegramSendResult.success();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == TOO_MANY_REQUESTS) {
                Long retryAfter = parseRetryAfterSeconds(e.getResponseBodyAsString());
                return retryAfter != null
                        ? TelegramSendResult.transientFailure(e.getMessage(), retryAfter)
                        : TelegramSendResult.transientFailure(e.getMessage());
            }
            return TelegramSendResult.permanentFailure(e.getMessage());
        } catch (HttpServerErrorException e) {
            return TelegramSendResult.transientFailure(e.getMessage());
        } catch (ResourceAccessException e) {
            return TelegramSendResult.transientFailure(e.getMessage());
        }
    }

    private Long parseRetryAfterSeconds(String responseBody) {
        try {
            JsonNode retryAfter =
                    objectMapper.readTree(responseBody).path("parameters").path("retry_after");
            return retryAfter.isMissingNode() ? null : retryAfter.asLong();
        } catch (Exception e) {
            return null;
        }
    }

    private record SendMessageRequest(
            @JsonProperty("chat_id") long chatId,
            String text,
            @JsonProperty("parse_mode") String parseMode,
            @JsonProperty("reply_markup") InlineKeyboardMarkup replyMarkup) {}

    private record InlineKeyboardMarkup(
            @JsonProperty("inline_keyboard") List<List<InlineKeyboardButton>> inlineKeyboard) {}

    private record InlineKeyboardButton(String text, String url) {}
}
