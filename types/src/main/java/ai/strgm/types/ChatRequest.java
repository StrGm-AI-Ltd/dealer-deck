package ai.strgm.types;

import ai.strgm.util.JsonFields;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * A chat completion request in the OpenAI format, as DEALER and DECK route it: the request's {@code json}, as the
 * client sent it, which they pass on with the fields they set (the model, the usage report), and the fields they act on.
 * <ul>
 * <li>{@code model}: the model it names, or null when it names none.</li>
 * <li>{@code stream}: whether it asks to stream; DEALER and DECK only stream.</li>
 * <li>{@code includeUsage}: whether it asks for the usage-only chunk ({@code stream_options.include_usage}).</li>
 * <li>{@code maxTokens}: the {@code max_tokens} it asks for, or zero when it names none.</li>
 * <li>{@code promptChars}: the characters of its messages' content, for a token estimate.</li>
 * </ul>
 */
public record ChatRequest(String json, @Nullable ModelName model, boolean stream, boolean includeUsage, long maxTokens, long promptChars) {

    /** Reads a request from its JSON. Throws {@link JacksonException} when the JSON isn't valid. */
    public static ChatRequest parse(final String json, final ObjectMapper mapper) {
        final var request = mapper.readTree(json);
        final var model = request.path("model");
        final var promptChars = request.path("messages").valueStream()
                .mapToLong(message -> JsonFields.text(message.path("content")).length())
                .sum();
        return new ChatRequest(json, model.isString() && !model.asString().isBlank() ? new ModelName(model.asString()) : null,
                JsonFields.bool(request.path("stream")), JsonFields.bool(request.path("stream_options").path("include_usage")),
                JsonFields.number(request.path("max_tokens")), promptChars);
    }
}
