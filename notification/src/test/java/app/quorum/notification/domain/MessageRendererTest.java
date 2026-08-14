package app.quorum.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.notification.api.NotificationTemplate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessageRendererTest {

    private final MessageRenderer renderer = new MessageRenderer("quorum_bot", "quorum-app");

    @Test
    void render_substitutesPlaceholders() {
        RenderedMessage rendered = renderer.render(
                NotificationTemplate.VOTING_STARTED,
                Map.of("hostName", "Denis", "sessionTitle", "Movie Night", "inviteCode", "ABC123"));

        assertThat(rendered.text()).isEqualTo("<b>Denis</b> запустил голосование в «Movie Night».\nГо выбирать фильм!");
        assertThat(rendered.buttonText()).isEqualTo("Открыть голосование");
    }

    @Test
    void render_escapesHtmlSpecialCharactersInParams() {
        RenderedMessage rendered = renderer.render(
                NotificationTemplate.SESSION_CANCELLED,
                Map.of("hostName", "Denis <script>", "sessionTitle", "A & B", "inviteCode", "ABC123"));

        assertThat(rendered.text()).isEqualTo("<b>Denis &lt;script&gt;</b> отменил «A &amp; B»");
    }

    @Test
    void render_buttonUrlContainsInviteCode() {
        RenderedMessage rendered = renderer.render(
                NotificationTemplate.WINNER_DECIDED, Map.of("movieTitle", "Dune", "inviteCode", "XYZ789"));

        assertThat(rendered.buttonUrl()).isEqualTo("https://t.me/quorum_bot/quorum-app?startapp=XYZ789");
    }
}
