package ai.strgm.deck;

import tools.jackson.databind.JsonNode;

/** Reading the fields of OpenAI-style requests and chunks. */
public final class Json {
    private Json() {}

    public static String text(final JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? "" : n.isValueNode() ? n.asString() : n.toString();
    }

    public static long number(final JsonNode n) {
        return n != null && n.isNumber() ? n.longValue() : 0;
    }

    public static boolean bool(final JsonNode n) {
        return n != null && n.isBoolean() && n.booleanValue();
    }

    /** Rough token estimate: characters / 4. */
    public static long promptTokens(final JsonNode request) {
        final var messages = request.path("messages");
        long chars = 0;
        for (int i = 0; i < messages.size(); i++) {
            chars += text(messages.get(i).path("content")).length();
        }
        return Math.max(1, chars / 4);
    }
}
