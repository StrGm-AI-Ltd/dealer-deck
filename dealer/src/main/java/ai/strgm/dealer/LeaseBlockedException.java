package ai.strgm.dealer;

import ai.strgm.types.LeaseId;
import java.util.List;

/** DECK refused a lease: {@code blockers} are the active leases in the way. */
public class LeaseBlockedException extends Exception {
    private final List<LeaseId> blockers;

    public LeaseBlockedException(final List<LeaseId> blockers) {
        super("blocked by " + blockers);
        this.blockers = blockers;
    }

    public List<LeaseId> blockers() {
        return blockers;
    }
}
