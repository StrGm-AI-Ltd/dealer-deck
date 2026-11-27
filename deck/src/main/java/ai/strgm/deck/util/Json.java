package ai.strgm.deck.util;

import static java.lang.Math.max;

import tools.jackson.databind.JsonNode;

/** Reading the fields of OpenAI-style requests and chunks. */
public final class Json {
    /** A rough estimate of the characters in one token. */
    private static final int CHARS_PER_TOKEN = 4;

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

    /** The prompt's tokens, estimated from its messages' characters. */
    public static long promptTokens(final JsonNode chatRequest) {
        final var chars = chatRequest.path("messages").valueStream()
                .mapToLong(message -> text(message.path("content")).length())
                .sum();
        return estimatedTokens(chars);
    }

    /** A rough estimate of the tokens in {@code chars} characters: {@value #CHARS_PER_TOKEN} characters per token, and at least one. */
    public static long estimatedTokens(final long chars) {
        return max(1, chars / CHARS_PER_TOKEN);
    }
}
