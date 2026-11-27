package ai.strgm.dealer.config;

import ai.strgm.dealer.api.model.ServerType;
import ai.strgm.types.ClientName;
import ai.strgm.types.Priority;
import ai.strgm.types.ServerName;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** DEALER's configuration; {@code evictionHistory} is how many eviction events {@code /metrics/state} keeps. */
@ConfigurationProperties("dealer")
public record DealerProperties(String metricsKey, Timers timers, Timeouts timeouts, int evictionHistory, List<ClientConfig> clients,
        List<DeckServerConfig> servers) {

    /** A client: its API key, priority (1–100; only this can evict) and whether it may use external servers (D79). */
    // TODO(#62): clients move to the database, and their DECK servers and model profiles replace externalRouting
    public record ClientConfig(ClientName name, String key, Priority priority, boolean externalRouting) {
    }

    /**
     * A DECK server. The operator classifies it as local or external (D94). DEALER holds its DECK key and, for
     * external servers, the provider credential it forwards in v0.5 (D104), read from an environment variable.
     */
    public record DeckServerConfig(ServerName name, String url, ServerType egress, String key, @Nullable String providerCredentialEnv) {}

    /**
     * M ({@code minLeaseSeconds}): how long a lease is ready before it can be evicted; N ({@code idleSeconds}): how
     * long a lease idles before it's released; how long a request waits for its lease to be ready, and how often it
     * asks DECK meanwhile; how often idle leases are released and the queue is dispatched ({@code tickMillis}); and
     * how often DEALER refreshes every DECK server's model profiles.
     */
    public record Timers(int minLeaseSeconds, int idleSeconds, int readinessTimeoutSeconds, int readinessPollMillis,
            int tickMillis, int deckRefreshMillis) {}

    /**
     * DEALER's calls to DECK: the lease and model-profile calls ({@code deck*}), and a forwarded chat request, whose
     * time limit covers its whole stream ({@code chat*}).
     */
    public record Timeouts(int deckConnectSeconds, int deckReadSeconds, int chatConnectSeconds, int chatRequestMinutes) {}
}
