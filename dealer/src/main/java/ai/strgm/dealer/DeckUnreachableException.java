package ai.strgm.dealer;

/** A DECK server couldn't be reached. */
public class DeckUnreachableException extends RuntimeException {
    public DeckUnreachableException(final String message) {
        super(message);
    }
}
