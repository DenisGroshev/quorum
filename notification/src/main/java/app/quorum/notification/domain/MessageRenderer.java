package app.quorum.notification.domain;

import app.quorum.notification.api.NotificationTemplate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.stereotype.Component;

@Component
public class MessageRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    private final Properties templates;
    private final String botUsername;
    private final String miniAppName;

    public MessageRenderer(
            @Value("${quorum.telegram.bot-username}") String botUsername,
            @Value("${quorum.telegram.miniapp-name}") String miniAppName) {
        this.botUsername = botUsername;
        this.miniAppName = miniAppName;
        this.templates = loadTemplates();
    }

    public RenderedMessage render(NotificationTemplate template, Map<String, String> params) {
        String key = template.name().toLowerCase(Locale.ROOT);
        String text = substitute(requireTemplate(key + ".text"), params);
        String buttonText = requireTemplate(key + ".button");
        return new RenderedMessage(text, buttonText, buttonUrl(params.get("inviteCode")));
    }

    private String substitute(String template, Map<String, String> params) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(escape(params.get(matcher.group(1)))));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String buttonUrl(String inviteCode) {
        return "https://t.me/" + botUsername + "/" + miniAppName + "?startapp=" + inviteCode;
    }

    private String requireTemplate(String key) {
        String value = templates.getProperty(key);
        if (value == null) {
            throw new IllegalStateException("Missing notification template: " + key);
        }
        return value;
    }

    private Properties loadTemplates() {
        try {
            return PropertiesLoaderUtils.loadProperties(new EncodedResource(
                    new ClassPathResource("messages/notifications.properties"), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
