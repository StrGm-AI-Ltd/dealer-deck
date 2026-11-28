package ai.strgm.util;

import org.jspecify.annotations.Nullable;

/**
 * The values of {@code Authorization} headers: the one place that knows how an API key or a provider credential is
 * presented, so another authentication scheme changes only this class. Both are bearer tokens today (RFC 6750).
 */
public final class AuthorizationHeaders {
    private static final String BEARER_PREFIX = "Bearer ";

    private AuthorizationHeaders() {}

    /** How one of our services presents its API key to another: DEALER to DECK, the DEALER UI to both. */
    public static String forApiKey(final String apiKey) {
        return BEARER_PREFIX + apiKey;
    }

    /** The API key an {@code Authorization} header presents, or null if it's missing or uses another scheme. */
    public static @Nullable String apiKeyIn(final @Nullable String authorization) {
        return authorization != null && authorization.startsWith(BEARER_PREFIX) ? authorization.substring(BEARER_PREFIX.length()) : null;
    }

    /** How an OpenAI-compatible provider expects its API key, which DEALER forwards through DECK (D104). */
    public static String forProviderCredential(final String providerCredential) {
        return BEARER_PREFIX + providerCredential;
    }
}
