package ai.strgm.deck.client.exception;

import ai.strgm.types.LeaseId;
import java.util.List;

/** A DECK server refused a lease (409): {@code blockers} are the active leases in the way. */
public class DeckLeaseConflictException extends DeckResponseException {
    private final List<LeaseId> blockers;

    public DeckLeaseConflictException(final int status, final String body, final List<LeaseId> blockers) {
        super(status, body);
        this.blockers = blockers;
    }

    public List<LeaseId> blockers() {
        return blockers;
    }
}
