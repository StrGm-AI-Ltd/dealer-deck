package ai.strgm.deck.client.exception;

/** A DECK server couldn't be reached, or the connection to it failed. */
public class DeckConnectionException extends RuntimeException {
    public DeckConnectionException(final String baseUrl, final Throwable cause) {
        super("DECK server at " + baseUrl + " unreachable", cause);
    }
}
