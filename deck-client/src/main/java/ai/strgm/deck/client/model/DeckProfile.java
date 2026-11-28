package ai.strgm.deck.client.model;

import ai.strgm.types.ModelName;
import ai.strgm.types.ProfileId;
import ai.strgm.types.TokenPrices;
import java.util.List;

/**
 * A model profile a DECK server offers, as a client routes and prices by it. {@code reducedProfileIds}: the profiles
 * on the same server whose leases a lease on this one reduces.
 */
public record DeckProfile(ProfileId id, ModelName modelName, int contextWindow, TokenPrices prices, List<ProfileId> reducedProfileIds) {

    /** Whether the profile's context window holds at least {@code minContextWindow} tokens. */
    public boolean fitsContext(final int minContextWindow) {
        return contextWindow >= minContextWindow;
    }

    /** Whether a lease on this profile reduces the leases {@code otherId} can grant. Any reduction counts. */
    public boolean reduces(final ProfileId otherId) {
        return reducedProfileIds.contains(otherId);
    }
}
