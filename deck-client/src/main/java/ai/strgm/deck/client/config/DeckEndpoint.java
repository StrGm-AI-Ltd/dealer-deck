package ai.strgm.deck.client.config;

import org.jspecify.annotations.Nullable;

/**
 * A DECK server: its base URL, the DECK key it requires, and the provider credential to forward with each chat
 * completion, if any (D104).
 */
public record DeckEndpoint(String baseUrl, String apiKey, @Nullable String providerCredential) {}
