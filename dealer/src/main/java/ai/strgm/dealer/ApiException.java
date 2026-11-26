package ai.strgm.dealer;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    final HttpStatus status;
    final @Nullable String type;
    final @Nullable String body;
    final @Nullable String param;

    public ApiException(final HttpStatus status, final String type, final String message) {
        this(status, type, message, (String) null);
    }

    /** {@code param}: the request field or header at fault. */
    public ApiException(final HttpStatus status, final String type, final String message, final @Nullable String param) {
        super(message);
        this.status = status;
        this.type = type;
        this.body = null;
        this.param = param;
    }

    /** Passes a DECK error body through unchanged. */
    public ApiException(final HttpStatus status, final String rawBody) {
        super("DECK error " + status);
        this.status = status;
        this.type = null;
        this.body = rawBody;
        this.param = null;
    }
}
