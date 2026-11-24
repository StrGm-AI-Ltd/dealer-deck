package ai.strgm.dealer;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    final HttpStatus status;
    final String type;
    final String body;

    public ApiException(final HttpStatus status, final String type, final String message) {
        super(message);
        this.status = status;
        this.type = type;
        this.body = null;
    }

    /** Passes a DECK error body through unchanged. */
    public ApiException(final HttpStatus status, final String rawBody) {
        super("DECK error " + status);
        this.status = status;
        this.type = null;
        this.body = rawBody;
    }
}
