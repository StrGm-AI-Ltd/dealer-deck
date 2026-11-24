package ai.strgm.dealer.ui;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ui")
public record UiProperties(Endpoint dealer, List<Endpoint> decks) {
    public record Endpoint(String name, String url, String key) {}
}
