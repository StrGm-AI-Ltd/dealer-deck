package ai.strgm.dealer;

import ai.strgm.types.ModelName;
import ai.strgm.types.ProfileId;
import java.util.List;

/**
 * A model profile a DECK server offers, as DEALER routes and prices by it. {@code reducedProfileIds}: the profiles on
 * the same server whose leases a lease on this one reduces.
 */
public record DeckProfile(ProfileId id, ModelName modelName, int contextWindow, TokenPrices prices, List<ProfileId> reducedProfileIds) {

    /** Prices per million tokens; zero where DECK states none. */
    // TODO(#104): money amounts with their currency, instead of bare doubles in an implied currency
    public record TokenPrices(double inputPerMTok, double cachedInputPerMTok, double outputPerMTok) {
        public static final TokenPrices NONE = new TokenPrices(0, 0, 0);
    }

    /** Whether a lease on this profile reduces the leases {@code otherId} can grant. Any reduction counts. */
    public boolean reduces(final ProfileId otherId) {
        return reducedProfileIds.contains(otherId);
    }
}
