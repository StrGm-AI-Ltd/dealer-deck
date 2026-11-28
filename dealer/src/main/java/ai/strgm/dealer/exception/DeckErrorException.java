package ai.strgm.dealer.exception;

/** A DECK server refused a chat request, with its status and error body, which DEALER passes on unchanged. */
public class DeckErrorException extends RuntimeException {
    private final int deckErrorStatus;
    private final String deckErrorBody;

    public DeckErrorException(final int deckErrorStatus, final String deckErrorBody) {
        super("DECK error " + deckErrorStatus);
        this.deckErrorStatus = deckErrorStatus;
        this.deckErrorBody = deckErrorBody;
    }

    public int deckErrorStatus() {
        return deckErrorStatus;
    }

    public String deckErrorBody() {
        return deckErrorBody;
    }
}
