package ai.strgm.dealer;

import ai.strgm.dealer.api.model.ServerType;
import ai.strgm.types.ClientName;
import ai.strgm.types.ServerName;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dealer")
public record DealerProperties(String metricsKey, Timers timers, List<ClientDef> clients, List<ServerDef> servers) {

    /** A client: its API key, priority (1–100; only this can evict) and whether it may use external servers (D79). */
    // TODO(#62): clients move to the database, and their DECK servers and model profiles replace externalRouting
    public record ClientDef(ClientName name, String key, int priority, boolean externalRouting) {
        public ClientDef {
            if (!Priorities.RANGE.contains(priority)) {
                throw new IllegalArgumentException("Client " + name + ": priority " + priority + " isn't between "
                        + Priorities.MIN + " and " + Priorities.MAX);
            }
        }
    }

    /**
     * A DECK server. The operator classifies it as local or external (D94). DEALER holds its DECK key and, for
     * external servers, the provider credential it forwards in v0.5 (D104), read from an environment variable.
     */
    public record ServerDef(ServerName name, String url, ServerType egress, String key, @Nullable String providerCredentialEnv) {}

    /** M: minimum lease time from ready before eviction; N: idle time after which a lease is released. */
    public record Timers(int minLeaseSeconds, int idleSeconds) {}
}
