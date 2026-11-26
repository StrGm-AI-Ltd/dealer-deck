package ai.strgm.deck;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/** An error that becomes an OpenAI-style response. {@code blockers} is set for lease conflicts. */
public class ApiException extends RuntimeException {
    final HttpStatus status;
    final String type;
    final @Nullable List<String> blockers;

    public ApiException(final HttpStatus status, final String type, final String message) {
        this(status, type, message, null);
    }

    public ApiException(final HttpStatus status, final String type, final String message, final @Nullable List<String> blockers) {
        super(message);
        this.status = status;
        this.type = type;
        this.blockers = blockers;
    }
}
