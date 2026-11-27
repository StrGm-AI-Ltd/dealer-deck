package ai.strgm.deck.client.exception;

/** A DECK server answered with an error status, and {@code body}, its error body as it sent it. */
public class DeckResponseException extends RuntimeException {
    private final int status;
    private final String body;

    public DeckResponseException(final int status, final String body) {
        super("DECK error " + status);
        this.status = status;
        this.body = body;
    }

    public int status() {
        return status;
    }

    public String body() {
        return body;
    }
}
