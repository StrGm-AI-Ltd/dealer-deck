package ai.strgm.dealer;

/** A DECK server refused a chat request, with its status and error body, which DEALER passes on unchanged. */
public class DeckErrorException extends RuntimeException {
    final int deckStatus;
    final String deckBody;

    public DeckErrorException(final int deckStatus, final String deckBody) {
        super("DECK error " + deckStatus);
        this.deckStatus = deckStatus;
        this.deckBody = deckBody;
    }
}
