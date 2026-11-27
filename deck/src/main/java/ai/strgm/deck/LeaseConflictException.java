package ai.strgm.deck;

import ai.strgm.types.LeaseId;
import java.util.List;

/** A profile has no capacity for another lease; {@code blockers} are the active leases in the way. */
public class LeaseConflictException extends RuntimeException {
    final List<LeaseId> blockers;

    public LeaseConflictException(final String message, final List<LeaseId> blockers) {
        super(message);
        this.blockers = blockers;
    }
}
