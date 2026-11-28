package ai.strgm.deck.openai.model;

import org.jspecify.annotations.Nullable;

/**
 * One server-sent event of the provider's chat completion.
 * <ul>
 * <li>{@code text}: the event as the provider sent it, to relay unchanged.</li>
 * <li>{@code usage}: the tokens it reports, if any.</li>
 * <li>{@code contentChars}: the characters of completion text its choices carry, for an estimate when the provider
 * reports no usage.</li>
 * </ul>
 */
public record ProviderChatEvent(String text, @Nullable ProviderUsage usage, long contentChars) {}
