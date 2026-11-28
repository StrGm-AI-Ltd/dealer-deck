package ai.strgm.util;

import tools.jackson.databind.JsonNode;

/** Reading the fields of OpenAI-style JSON, where a field that's missing or of another type reads as empty, zero or false. */
public final class JsonFields {
    private JsonFields() {}

    /** A field's text: a value's own text, or the JSON of an object or an array; empty when the field is missing or null. */
    public static String text(final JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.isValueNode() ? node.asString() : node.toString();
    }

    /** A number field's value, or zero. */
    public static long number(final JsonNode node) {
        return node != null && node.isNumber() ? node.longValue() : 0;
    }

    /** A boolean field's value, or false. */
    public static boolean bool(final JsonNode node) {
        return node != null && node.isBoolean() && node.booleanValue();
    }
}
