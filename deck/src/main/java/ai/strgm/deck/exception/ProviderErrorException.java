package ai.strgm.deck.exception;

import org.jspecify.annotations.Nullable;

/**
 * The provider behind a DECK server refused a chat request. {@code body} is its error body, passed on unchanged, and
 * blank when it sent none; {@code retryAfter} is its {@code Retry-After}, if any.
 */
public class ProviderErrorException extends RuntimeException {
    /** What the provider's refusal means for the caller. */
    public enum Reason {
        /** Too many requests: retry later. */
        RATE_LIMITED,
        /** The forwarded credential was rejected. */
        CREDENTIAL_REJECTED,
        /** The request itself was rejected, e.g. an unknown model or invalid content. */
        REQUEST_REJECTED,
        /** Any other error. */
        UNAVAILABLE,
        /** The provider answered with an error whose body couldn't be read. */
        UNREADABLE
    }

    private final Reason reason;
    private final String body;
    private final @Nullable String retryAfter;

    public ProviderErrorException(final Reason reason, final String message, final String body, final @Nullable String retryAfter) {
        super(message);
        this.reason = reason;
        this.body = body;
        this.retryAfter = retryAfter;
    }

    public Reason reason() {
        return reason;
    }

    public String body() {
        return body;
    }

    public @Nullable String retryAfter() {
        return retryAfter;
    }
}
