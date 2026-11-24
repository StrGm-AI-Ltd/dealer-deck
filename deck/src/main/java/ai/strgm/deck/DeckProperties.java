package ai.strgm.deck;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("deck")
public record DeckProperties(String serverName, String apiKey, double timeFactor, Nebius nebius) {

    public record Nebius(String baseUrl, List<NebiusModel> models) {}

    public record NebiusModel(String model, int contextWindow, double inputPerMTok, double cachedInputPerMTok,
            double outputPerMTok) {}
}
