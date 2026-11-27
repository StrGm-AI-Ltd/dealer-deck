package ai.strgm.deck.client;

import static java.nio.charset.StandardCharsets.UTF_8;

import ai.strgm.deck.client.model.ChatUsage;
import ai.strgm.deck.client.model.DeckChatEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** A chat completion's server-sent events, read one event at a time. Close it to release the connection. */
public final class DeckChatStream implements AutoCloseable {
    /** The prefix of a server-sent event's data line. */
    private static final String SSE_DATA_PREFIX = "data:";

    private final BufferedReader reader;
    private final ObjectMapper mapper;

    DeckChatStream(final InputStream events, final ObjectMapper mapper) {
        this.reader = new BufferedReader(new InputStreamReader(events, UTF_8));
        this.mapper = mapper;
    }

    /** The next event, or null once DECK has ended the stream. */
    public @Nullable DeckChatEvent nextEvent() throws IOException {
        final var text = new StringBuilder();
        ChatUsage usage = null;
        boolean usageOnly = false;
        String line;
        while ((line = reader.readLine()) != null) {
            text.append(line).append('\n');
            if (line.isEmpty()) {
                return new DeckChatEvent(text.toString(), usage, usageOnly);
            }
            if (line.startsWith(SSE_DATA_PREFIX)) {
                final var eventData = line.substring(SSE_DATA_PREFIX.length()).trim();
                if (eventData.startsWith("{")) {
                    final var chunk = mapper.readTree(eventData);
                    final var reportedUsage = chunk.path("usage");
                    if (reportedUsage.isObject()) {
                        usage = new ChatUsage(number(reportedUsage.path("prompt_tokens")),
                                number(reportedUsage.path("prompt_tokens_details").path("cached_tokens")),
                                number(reportedUsage.path("completion_tokens")));
                        // Nebius attaches usage to every chunk: only a usage-only chunk (no choices) is the one a
                        // client gets only when it asks for usage
                        usageOnly = chunk.path("choices").isEmpty();
                    }
                }
            }
        }
        return text.isEmpty() ? null : new DeckChatEvent(text.toString(), usage, usageOnly);
    }

    private static long number(final JsonNode node) {
        return node.isNumber() ? node.longValue() : 0;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
