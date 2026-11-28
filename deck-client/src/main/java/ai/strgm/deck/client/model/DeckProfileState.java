package ai.strgm.deck.client.model;

import ai.strgm.deck.api.model.Readiness;
import ai.strgm.types.ProfileId;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A model profile's state on its DECK server.
 * <ul>
 * <li>{@code loadingTime}: how long loading it takes, if DECK says.</li>
 * <li>{@code activeLeases}, and {@code maxLeases}: its budget of leases, or null when it has none.</li>
 * <li>{@code reductions}: what a lease on it takes from the other profiles' budgets.</li>
 * </ul>
 */
public record DeckProfileState(ProfileId id, int contextWindow, Readiness readiness, @Nullable Duration loadingTime, int activeLeases,
        @Nullable Integer maxLeases, List<Reduction> reductions) {

    /** A lease on the profile reduces {@code profileId}'s budget by {@code leases}. */
    public record Reduction(ProfileId profileId, int leases) {}
}
