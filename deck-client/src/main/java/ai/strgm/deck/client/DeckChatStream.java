package ai.strgm.deck.client;

import ai.strgm.deck.client.model.ChatUsage;
import ai.strgm.deck.client.model.DeckChatEvent;
import ai.strgm.util.JsonFields;
import ai.strgm.util.ServerSentEventReader;
import java.io.IOException;
import java.io.InputStream;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/** A chat completion's server-sent events, read one event at a time. Close it to release the connection. */
public final class DeckChatStream implements AutoCloseable {
    private final ServerSentEventReader events;
    private final ObjectMapper mapper;

    DeckChatStream(final InputStream events, final ObjectMapper mapper) {
        this.events = new ServerSentEventReader(events);
        this.mapper = mapper;
    }

    /** The next event, or null once DECK has ended the stream. */
    public @Nullable DeckChatEvent nextEvent() throws IOException {
        final var event = events.nextEvent();
        if (event == null) {
            return null;
        }
        // DECK sends one chunk per event; if an event carried several, the last one to report usage would count
        final var usageChunk = event.data().stream()
                .filter(eventData -> eventData.startsWith("{"))
                .map(mapper::readTree)
                .filter(chunk -> chunk.path("usage").isObject())
                .reduce((earlierChunk, laterChunk) -> laterChunk)
                .orElse(null);
        if (usageChunk == null) {
            return new DeckChatEvent(event.text(), null, false);
        }
        final var usage = usageChunk.path("usage");
        return new DeckChatEvent(event.text(), new ChatUsage(JsonFields.number(usage.path("prompt_tokens")),
                JsonFields.number(usage.path("prompt_tokens_details").path("cached_tokens")), JsonFields.number(usage.path("completion_tokens"))),
                // Nebius attaches usage to every chunk: only a usage-only chunk (no choices) is the one a client gets
                // only when it asks for usage
                usageChunk.path("choices").isEmpty());
    }

    @Override
    public void close() throws IOException {
        events.close();
    }
}
