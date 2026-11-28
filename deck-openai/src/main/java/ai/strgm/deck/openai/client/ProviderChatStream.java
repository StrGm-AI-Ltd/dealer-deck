package ai.strgm.deck.openai.client;

import static ai.strgm.util.Nulls.mapNullable;

import ai.strgm.deck.openai.model.ProviderChatEvent;
import ai.strgm.deck.openai.model.ProviderUsage;
import ai.strgm.util.JsonFields;
import ai.strgm.util.ServerSentEventReader;
import java.io.IOException;
import java.io.InputStream;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The provider's server-sent events for one chat completion, read one event at a time. Close it to release the connection. */
public final class ProviderChatStream implements AutoCloseable {
    private final ServerSentEventReader events;
    private final ObjectMapper mapper;

    ProviderChatStream(final InputStream events, final ObjectMapper mapper) {
        this.events = new ServerSentEventReader(events);
        this.mapper = mapper;
    }

    /** The next event, or null once the provider has ended the stream. */
    public @Nullable ProviderChatEvent nextEvent() throws IOException {
        final var event = events.nextEvent();
        if (event == null) {
            return null;
        }
        // a provider sends one chunk per event; if an event carried several, the last one to report usage would count
        final var chunks = event.data().stream()
                .filter(eventData -> eventData.startsWith("{"))
                .map(mapper::readTree)
                .toList();
        final var usageChunk = chunks.stream()
                .filter(chunk -> chunk.path("usage").isObject())
                .reduce((earlierChunk, laterChunk) -> laterChunk)
                .orElse(null);
        final var contentChars = chunks.stream()
                .flatMap(chunk -> chunk.path("choices").valueStream())
                .mapToLong(choice -> JsonFields.text(choice.path("delta").path("content")).length())
                .sum();
        return new ProviderChatEvent(event.text(), mapNullable(usageChunk, chunk -> providerUsage(chunk.path("usage"))), contentChars);
    }

    private static ProviderUsage providerUsage(final JsonNode usage) {
        return new ProviderUsage(JsonFields.number(usage.path("prompt_tokens")), JsonFields.number(usage.path("prompt_tokens_details").path("cached_tokens")),
                JsonFields.number(usage.path("completion_tokens")));
    }

    @Override
    public void close() throws IOException {
        events.close();
    }
}
