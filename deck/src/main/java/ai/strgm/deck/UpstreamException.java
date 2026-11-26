package ai.strgm.deck;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/** A provider error passed back to DEALER as an OpenAI-style response, with any Retry-After. */
public class UpstreamException extends RuntimeException {
    final HttpStatus status;
    final String body;
    final @Nullable String retryAfter;

    public UpstreamException(final HttpStatus status, final String body, final @Nullable String retryAfter) {
        super("Upstream error " + status);
        this.status = status;
        this.body = body;
        this.retryAfter = retryAfter;
    }
}
