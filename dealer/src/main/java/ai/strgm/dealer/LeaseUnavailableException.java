package ai.strgm.dealer;

/** A request couldn't get a ready lease: it was interrupted while queued, or its lease never became ready. */
public class LeaseUnavailableException extends RuntimeException {
    public LeaseUnavailableException(final String message) {
        super(message);
    }
}
