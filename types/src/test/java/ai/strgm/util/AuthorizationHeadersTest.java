package ai.strgm.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** How API keys and provider credentials are presented in, and read from, {@code Authorization} headers. */
class AuthorizationHeadersTest {
    private static final String API_KEY = "deck-key-stub";
    private static final String PROVIDER_CREDENTIAL = "provider-key";

    @Test
    void anApiKeyReadsBackFromItsHeader() {
        assertEquals(API_KEY, AuthorizationHeaders.apiKeyIn(AuthorizationHeaders.forApiKey(API_KEY)));
    }

    @Test
    void aMissingHeaderOrAnotherSchemePresentsNoApiKey() {
        assertNull(AuthorizationHeaders.apiKeyIn(null));
        assertNull(AuthorizationHeaders.apiKeyIn("Basic ZGVhbGVyOmtleQ=="));
        assertNull(AuthorizationHeaders.apiKeyIn(API_KEY));
    }

    /** The scheme the DEALER and DECK documents declare, and the one OpenAI-compatible providers expect. */
    @Test
    void bothAreBearerTokens() {
        assertEquals("Bearer " + API_KEY, AuthorizationHeaders.forApiKey(API_KEY));
        assertEquals("Bearer " + PROVIDER_CREDENTIAL, AuthorizationHeaders.forProviderCredential(PROVIDER_CREDENTIAL));
    }
}
