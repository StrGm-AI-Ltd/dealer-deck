package ai.strgm.deck;

/** The provider behind a DECK server couldn't be reached. */
public class ProviderUnreachableException extends RuntimeException {
    public ProviderUnreachableException(final String message) {
        super(message);
    }
}
