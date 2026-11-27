package ai.strgm.deck;

import ai.strgm.types.ServerName;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What every DECK server is configured with: its name, the DECK key DEALER must send (D104), and how many of the
 * most recent leases {@code /metrics/state} reports.
 */
@ConfigurationProperties("deck")
public record DeckProperties(ServerName serverName, String apiKey, int recentLeases) {}
