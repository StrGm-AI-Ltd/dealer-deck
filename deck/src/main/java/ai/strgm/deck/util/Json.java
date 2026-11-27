package ai.strgm.deck.util;

import static java.lang.Math.max;

import tools.jackson.databind.JsonNode;

/** Reading the fields of OpenAI-style requests and chunks. */
public final class Json {
    /** A rough estimate of the characters in one token. */
    public static final int CHARS_PER_TOKEN = 4;

    private Json() {}

    public static String text(final JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.isValueNode() ? node.asString() : node.toString();
    }

    public static long number(final JsonNode node) {
        return node != null && node.isNumber() ? node.longValue() : 0;
    }

    public static boolean bool(final JsonNode node) {
        return node != null && node.isBoolean() && node.booleanValue();
    }

    /** A rough token estimate: characters / {@value #CHARS_PER_TOKEN}. */
    public static long promptTokens(final JsonNode request) {
        final var messages = request.path("messages");
        long chars = 0;
        for (int messageIndex = 0; messageIndex < messages.size(); messageIndex++) {
            chars += text(messages.get(messageIndex).path("content")).length();
        }
        return max(1, chars / CHARS_PER_TOKEN);
    }
}
