package ai.strgm.dealer.ui.client;

import ai.strgm.deck.client.DeckClient;
import ai.strgm.deck.client.config.DeckEndpoint;
import ai.strgm.deck.client.config.DeckTimeouts;
import ai.strgm.dealer.ui.config.UiProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** A {@link DeckClient} for each DECK server in the configuration. */
@Component
public class DeckClients {
    private final Map<String, DeckClient> clientsByName = new LinkedHashMap<>();

    public DeckClients(final UiProperties properties, final ObjectMapper mapper) {
        final var connectTimeout = Duration.ofSeconds(properties.connectTimeoutSeconds());
        final var readTimeout = Duration.ofSeconds(properties.readTimeoutSeconds());
        // the view only reads each server's state, so a chat completion's timeouts are the same as the others
        final var timeouts = new DeckTimeouts(connectTimeout, readTimeout, connectTimeout, readTimeout);
        properties.decks().forEach(deckConfig -> clientsByName.put(deckConfig.name(),
                new DeckClient(new DeckEndpoint(deckConfig.url(), deckConfig.key(), null), timeouts, mapper)));
    }

    /** Each DECK server's client, by the server's name in the configuration. */
    public Map<String, DeckClient> clientsByName() {
        return clientsByName;
    }
}
