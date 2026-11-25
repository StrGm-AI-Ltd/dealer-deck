package ai.strgm.deck.openai;

import ai.strgm.deck.api.model.Implementation;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The OpenAI-compatible provider behind this server. {@code provider}: its name, e.g. {@code nebius} or
 * {@code omlx}; {@code implementation}: what the server reports, {@code external} for a cloud provider or
 * {@code local} for the operator's own hardware; {@code idPrefix}: the prefix of its profile ids (default
 * {@code <provider>/}).
 */
@ConfigurationProperties("deck.upstream")
public record UpstreamProperties(String provider, Implementation implementation, String baseUrl, String idPrefix,
        List<Model> models) {

    /**
     * {@code maxLeases}: the model's instance budget (null = unbounded), e.g. to cap an expensive model (D106).
     * {@code quantization} and {@code bitsPerWeight}: how the weights are stored, as the provider reports it,
     * e.g. in Nebius's model catalogue (null = unknown).
     */
    public record Model(String model, int contextWindow, double inputPerMTok, double cachedInputPerMTok,
            double outputPerMTok, Integer maxLeases, String quantization, Double bitsPerWeight) {}
}
