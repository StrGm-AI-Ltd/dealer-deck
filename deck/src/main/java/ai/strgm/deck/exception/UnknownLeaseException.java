package ai.strgm.deck.exception;

import ai.strgm.types.LeaseId;

/** This DECK server never granted such a lease. */
public class UnknownLeaseException extends RuntimeException {
    public UnknownLeaseException(final LeaseId leaseId) {
        super("Unknown lease: " + leaseId);
    }
}
