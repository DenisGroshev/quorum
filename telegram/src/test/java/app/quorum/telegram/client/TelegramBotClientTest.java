package app.quorum.telegram.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
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

    private TelegramBotClient client() {
        return new TelegramBotClient(RestClient.builder(), "http://localhost:" + wireMock.port(), BOT_TOKEN);
    }
}
