package ai.strgm.dealer;

import ai.strgm.dealer.DealerProperties.ClientConfig;
import ai.strgm.dealer.api.model.ServerType;
import ai.strgm.types.ServerName;
import java.util.List;

/** DEALER's state at one moment: its clients, its DECK servers, the queue, the sessions and the recent evictions. */
public record DealerSnapshot(List<ClientConfig> clients, List<ServerStatus> servers, List<LeaseRequestSnapshot> queue,
        List<SessionSnapshot> sessions, List<Eviction> evictions) {

    /** A DECK server: whether DEALER reached it at its last refresh, and how many profiles it offers. */
    public record ServerStatus(ServerName serverName, String url, ServerType serverType, boolean reachable, int profiles) {}
}
