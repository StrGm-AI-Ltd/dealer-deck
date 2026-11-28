package ai.strgm.deck.openai.model;

/** The tokens a chat completion used, as the provider reported them. */
public record ProviderUsage(long promptTokens, long cachedTokens, long completionTokens) {}
