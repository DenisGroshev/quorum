package app.quorum.telegram.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class TelegramBotClientTest {

    private static final String BOT_TOKEN = "test-token";

    private static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @AfterEach
    void resetStubs() {
        wireMock.resetAll();
    }

    @Test
    void sendMessage_success_returnsSuccessOutcome() {
        wireMock.stubFor(post(urlPathEqualTo("/bot" + BOT_TOKEN + "/sendMessage"))
                .willReturn(okJson("{\"ok\":true,\"result\":{}}")));

        TelegramSendResult result = client().sendMessage(123L, "hello");

        assertThat(result.outcome()).isEqualTo(TelegramSendOutcome.SUCCESS);
    }

    @Test
    void sendMessage_rateLimited_returnsTransientFailure() {
        wireMock.stubFor(post(urlPathEqualTo("/bot" + BOT_TOKEN + "/sendMessage"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests\"}")));

        TelegramSendResult result = client().sendMessage(123L, "hello");

        assertThat(result.outcome()).isEqualTo(TelegramSendOutcome.TRANSIENT_FAILURE);
    }

    @Test
    void sendMessage_serverError_returnsTransientFailure() {
        wireMock.stubFor(post(urlPathEqualTo("/bot" + BOT_TOKEN + "/sendMessage"))
                .willReturn(aResponse().withStatus(500)));

        TelegramSendResult result = client().sendMessage(123L, "hello");

        assertThat(result.outcome()).isEqualTo(TelegramSendOutcome.TRANSIENT_FAILURE);
    }

    @Test
    void sendMessage_botBlockedByUser_returnsPermanentFailure() {
        wireMock.stubFor(post(urlPathEqualTo("/bot" + BOT_TOKEN + "/sendMessage"))
                .willReturn(aResponse()
                        .withStatus(403)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was blocked by"
                                + " the user\"}")));

        TelegramSendResult result = client().sendMessage(123L, "hello");

        assertThat(result.outcome()).isEqualTo(TelegramSendOutcome.PERMANENT_FAILURE);
    }

    @Test
    void sendMessage_chatNotFound_returnsPermanentFailure() {
        wireMock.stubFor(post(urlPathEqualTo("/bot" + BOT_TOKEN + "/sendMessage"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: chat not"
                                + " found\"}")));

        TelegramSendResult result = client().sendMessage(123L, "hello");

        assertThat(result.outcome()).isEqualTo(TelegramSendOutcome.PERMANENT_FAILURE);
    }

    @Test
    void sendMessage_withButton_includesInlineKeyboardInRequestBody() {
        wireMock.stubFor(post(urlPathEqualTo("/bot" + BOT_TOKEN + "/sendMessage"))
                .willReturn(okJson("{\"ok\":true,\"result\":{}}")));

        TelegramSendResult result = client().sendMessage(123L, "hello", "Open", "https://t.me/quorum_bot/app");

        assertThat(result.outcome()).isEqualTo(TelegramSendOutcome.SUCCESS);
        wireMock.verify(postRequestedFor(urlPathEqualTo("/bot" + BOT_TOKEN + "/sendMessage"))
                .withRequestBody(matchingJsonPath("$.reply_markup.inline_keyboard[0][0].text", equalTo("Open")))
                .withRequestBody(matchingJsonPath(
                        "$.reply_markup.inline_keyboard[0][0].url", equalTo("https://t.me/quorum_bot/app"))));
    }

    @Test
    void sendMessage_rateLimited_returnsRetryAfterSecondsFromResponseBody() {
        wireMock.stubFor(post(urlPathEqualTo("/bot" + BOT_TOKEN + "/sendMessage"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after"
                                + " 5\",\"parameters\":{\"retry_after\":5}}")));

        TelegramSendResult result = client().sendMessage(123L, "hello");

        assertThat(result.outcome()).isEqualTo(TelegramSendOutcome.TRANSIENT_FAILURE);
        assertThat(result.retryAfterSeconds()).isEqualTo(5L);
    }

    @Test
    void sendMessage_connectionFailure_returnsTransientFailure() throws IOException {
        int unboundPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unboundPort = socket.getLocalPort();
        }
        TelegramBotClient offlineClient = new TelegramBotClient(
                RestClient.builder(), "http://localhost:" + unboundPort, BOT_TOKEN, new ObjectMapper());

        TelegramSendResult result = offlineClient.sendMessage(123L, "hello");

        assertThat(result.outcome()).isEqualTo(TelegramSendOutcome.TRANSIENT_FAILURE);
    }

    private TelegramBotClient client() {
        return new TelegramBotClient(
                RestClient.builder(), "http://localhost:" + wireMock.port(), BOT_TOKEN, new ObjectMapper());
    }
}
