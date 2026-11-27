package ai.strgm.dealer;

/** No DECK profile serves the requested model for the client. */
public class ModelNotFoundException extends RuntimeException {
    public ModelNotFoundException(final String message) {
        super(message);
    }
}
