package ai.strgm.deck.client.model;

/** The tokens a chat completion used, as DECK reported them. */
public record ChatUsage(long promptTokens, long cachedTokens, long completionTokens) {}
