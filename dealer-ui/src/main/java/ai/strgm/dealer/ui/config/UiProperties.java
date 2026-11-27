package ai.strgm.dealer.ui.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ui")
public record UiProperties(Endpoint dealer, List<Endpoint> decks, int refreshMillis, int connectTimeoutSeconds,
        int readTimeoutSeconds) {
    public record Endpoint(String name, String url, String key) {}
}
