package ai.strgm.deck.client.model;

import org.jspecify.annotations.Nullable;

/**
 * One server-sent event of a chat completion.
 * <ul>
 * <li>{@code text}: the event as DECK sent it, to relay unchanged: its lines, each ending in {@code \n}, and the blank
 * line that ends it, unless the stream ended first.</li>
 * <li>{@code usage}: the tokens it reports, if any.</li>
 * <li>{@code usageOnly}: it reports usage and carries no choices, so it's the chunk a client gets only when it asks for
 * usage ({@code stream_options.include_usage}).</li>
 * </ul>
 */
public record DeckChatEvent(String text, @Nullable ChatUsage usage, boolean usageOnly) {}
