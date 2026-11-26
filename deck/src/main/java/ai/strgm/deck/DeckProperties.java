package ai.strgm.deck;

import ai.strgm.types.ServerName;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** What every DECK server is configured with: its name, and the DECK key DEALER must send (D104). */
@ConfigurationProperties("deck")
public record DeckProperties(ServerName serverName, String apiKey) {}
