package ai.strgm.dealer;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dealer")
public record DealerProperties(String metricsKey, Timers timers, List<ClientDef> clients, List<ServerDef> servers) {

    /** A client: its API key, priority (1–100; only this can evict) and whether it may use external servers (D79). */
    public record ClientDef(String name, String key, int priority, boolean externalRouting) {}

    /**
     * A DECK server. The operator classifies it as local or external (D94). DEALER holds its DECK key and, for
     * external servers, the provider credential it forwards in v0.5 (D104), read from an environment variable.
     */
    public record ServerDef(String name, String url, String egress, String key, @Nullable String providerCredentialEnv) {}

    /** M: minimum lease time from ready before eviction; N: idle time after which a lease is released. */
    public record Timers(int minLeaseSeconds, int idleSeconds) {}
}
