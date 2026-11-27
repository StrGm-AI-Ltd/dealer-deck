package ai.strgm.dealer.ui.client;

import ai.strgm.deck.api.client.MetricsApi;
import ai.strgm.dealer.ui.config.UiProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The generated HTTP-interface clients for each DECK server's metrics API. */
@Component
public class DeckClients {
    private final Map<String, MetricsApi> metricsApis = new LinkedHashMap<>();

    public DeckClients(final UiProperties properties) {
        properties.decks().forEach(deckEndpoint -> metricsApis.put(deckEndpoint.name(),
                ProxyFactories.forEndpoint(deckEndpoint, properties).createClient(MetricsApi.class)));
    }

    /** Each DECK server's metrics API, by the server's name in the configuration. */
    public Map<String, MetricsApi> metricsApis() {
        return metricsApis;
    }
}
