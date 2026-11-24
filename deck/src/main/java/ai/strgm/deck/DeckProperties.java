package ai.strgm.deck;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("deck")
public record DeckProperties(String serverName, String apiKey, double timeFactor, Nebius nebius) {

    public record Nebius(String baseUrl, List<NebiusModel> models) {}

    /**
     * {@code maxLeases}: the model's instance budget (null = unbounded), e.g. to cap an expensive model (D106).
     * {@code quantization} and {@code bitsPerWeight}: how the weights are stored, as Nebius's model catalogue
     * reports it (null = unknown).
     */
    public record NebiusModel(String model, int contextWindow, double inputPerMTok, double cachedInputPerMTok,
            double outputPerMTok, Integer maxLeases, String quantization, Double bitsPerWeight) {}
}
