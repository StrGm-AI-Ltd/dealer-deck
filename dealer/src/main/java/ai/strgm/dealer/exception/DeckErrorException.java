package ai.strgm.dealer.exception;

/** A DECK server refused a chat request, with its status and error body, which DEALER passes on unchanged. */
public class DeckErrorException extends RuntimeException {
    private final int deckStatus;
    private final String deckBody;

    public DeckErrorException(final int deckStatus, final String deckBody) {
        super("DECK error " + deckStatus);
        this.deckStatus = deckStatus;
        this.deckBody = deckBody;
    }

    public int deckStatus() {
        return deckStatus;
    }

    public String deckBody() {
        return deckBody;
    }
}
