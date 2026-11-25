package ai.strgm.deck;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** What every DECK server is configured with: its name, and the DECK key DEALER must send (D104). */
@ConfigurationProperties("deck")
public record DeckProperties(String serverName, String apiKey) {}
