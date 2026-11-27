package ai.strgm.dealer;

import static java.nio.charset.StandardCharsets.UTF_8;

import ai.strgm.types.ProfileId;
import ai.strgm.types.ServerName;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A completion streaming from DECK. It relays DECK's server-sent events, drops the usage-only chunk the client didn't
 * ask for, and ends the request on its lease with the usage DECK reported.
 */
public final class ChatStream {
    /** The prefix of a server-sent event's data line. */
    private static final String SSE_DATA_PREFIX = "data:";

    private final Placement placement;
    private final InputStream deckEvents;
    private final boolean clientWantsUsage;
    private final Scheduler scheduler;
    private final ObjectMapper mapper;

    ChatStream(final Placement placement, final InputStream deckEvents, final boolean clientWantsUsage, final Scheduler scheduler,
            final ObjectMapper mapper) {
        this.placement = placement;
        this.deckEvents = deckEvents;
        this.clientWantsUsage = clientWantsUsage;
        this.scheduler = scheduler;
        this.mapper = mapper;
    }

    /** The DECK server serving the completion. */
    public ServerName serverName() {
        return placement.profileRef().serverRef().name();
    }

    /** The model profile serving the completion. */
    public ProfileId profileId() {
        return placement.profileRef().profile().id();
    }

    /** Copies DECK's events to {@code out} until DECK ends the stream, then ends the request on its lease. */
    public void relayTo(final OutputStream out) throws IOException {
        long promptTokens = 0;
        long cachedTokens = 0;
        long completionTokens = 0;
        boolean skipBlank = false;
        try (var reader = new BufferedReader(new InputStreamReader(deckEvents, UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(SSE_DATA_PREFIX)) {
                    final var eventData = line.substring(SSE_DATA_PREFIX.length()).trim();
                    if (eventData.startsWith("{")) {
                        final var chunk = mapper.readTree(eventData);
                        final var usage = chunk.path("usage");
                        if (usage.isObject()) {
                            promptTokens = number(usage.path("prompt_tokens"));
                            cachedTokens = number(usage.path("prompt_tokens_details").path("cached_tokens"));
                            completionTokens = number(usage.path("completion_tokens"));
                            // Nebius attaches usage to every chunk: only a usage-only chunk (no choices) is the
                            // one the client didn't ask for, so a chunk that carries text is always forwarded
                            if (!clientWantsUsage && chunk.path("choices").isEmpty()) {
                                skipBlank = true;
                                continue;
                            }
                        }
                    }
                }
                if (line.isEmpty() && skipBlank) {
                    skipBlank = false;
                    continue;
                }
                out.write((line + "\n").getBytes(UTF_8));
                if (line.isEmpty()) {
                    out.flush();
                }
            }
        } finally {
            scheduler.release(placement, promptTokens, cachedTokens, completionTokens);
        }
    }

    private static long number(final JsonNode node) {
        return node.isNumber() ? node.longValue() : 0;
    }
}
