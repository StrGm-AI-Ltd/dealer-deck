package ai.strgm.deck;

import org.springframework.http.HttpStatus;

/** A request DECK rejects, which becomes an OpenAI-style response. */
public class ApiException extends RuntimeException {
    final HttpStatus status;
    final String type;

    public ApiException(final HttpStatus status, final String type, final String message) {
        super(message);
        this.status = status;
        this.type = type;
    }
}
