package ai.strgm.deck;

import ai.strgm.deck.api.model.Implementation;
import ai.strgm.deck.api.model.WorkerState;
import ai.strgm.types.ServerName;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A DECK server's state at one moment: its profiles, its most recent leases, its workers (for a simulated server) and
 * what its leases have cost. {@code provider}: the provider behind it, for a provider-backed server.
 */
// TODO(#104): spendUsd becomes a money amount with its currency, instead of a bare double whose currency only its name gives
public record ServerState(ServerName serverName, Implementation implementation, @Nullable String provider, List<OfferedProfile> profiles,
        List<LeaseSnapshot> leases, List<Worker> workers, double spendUsd) {

    /** A simulated worker: the profile loaded on it, or {@code loading <profile id>}, and whether it's leased. */
    public record Worker(String id, String loadedProfile, WorkerState state) {}
}
