package ai.strgm.deck;

import ai.strgm.deck.api.model.LeaseState;
import ai.strgm.types.LeaseId;

/** A chat request names a lease that was released or evicted. */
public class LeaseInactiveException extends RuntimeException {
    public LeaseInactiveException(final LeaseId leaseId, final LeaseState state) {
        super("Lease " + leaseId + " is " + state.getValue());
    }
}
