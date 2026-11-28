package ai.strgm.util;

import java.util.List;

/**
 * One server-sent event: its {@code text} as the stream sent it, to relay unchanged (its lines, each ending in
 * {@code \n}, and the blank line that ends it, unless the stream ended first), and the values of its {@code data} lines.
 */
public record ServerSentEvent(String text, List<String> data) {}
