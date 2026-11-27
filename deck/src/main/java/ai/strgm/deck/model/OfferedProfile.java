package ai.strgm.deck.model;

import ai.strgm.deck.api.model.Readiness;
import ai.strgm.types.ModelName;
import ai.strgm.types.ProfileId;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A model profile a DECK server offers: its model and context window, how its weights are stored, whether it's loaded,
 * its prices and its lease capacity. {@code provider}: the provider behind it, for a provider-backed server;
 * {@code leaseReductions}: what a lease on it takes from the server's other profiles; {@code maxLeases}: its lease
 * budget (null = unbounded); {@code speeds}: its prefill and decode speeds by context size, for a simulated server.
 */
public record OfferedProfile(ProfileId id, ModelName modelName, @Nullable String provider, int contextWindow,
        @Nullable Quantization quantization, Readiness readiness, @Nullable Integer loadingTimeMs, List<LeaseReduction> leaseReductions,
        Prices prices, @Nullable Integer maxLeases, int activeLeases, List<SpeedPoint> speeds) {

    /** How the weights are stored, e.g. {@code q4_k_m}, and their bits per weight, if known. */
    public record Quantization(String format, @Nullable Double bitsPerWeight) {}

    /** A lease on the profile takes {@code leases} leases from profile {@code profileId}. */
    public record LeaseReduction(ProfileId profileId, int leases) {}

    /** Prices per million tokens. */
    // TODO(#104): money amounts with their currency, instead of bare doubles in an implied currency
    public record Prices(double inputPerMTok, double cachedInputPerMTok, double outputPerMTok) {}

    /** The prefill and decode speeds at a context size. */
    public record SpeedPoint(int contextTokens, double prefillTokensPerSecond, double decodeTokensPerSecond) {}
}
