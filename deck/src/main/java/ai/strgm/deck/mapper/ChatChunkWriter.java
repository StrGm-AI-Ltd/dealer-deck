package ai.strgm.deck.mapper;

import static java.nio.charset.StandardCharsets.UTF_8;

import ai.strgm.types.ModelName;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes one chat completion in the OpenAI streaming format: its chunks as server-sent events, each flushed as it's
 * written, then the {@code [DONE]} event. A DECK server that produces a completion itself writes it through this.
 */
public final class ChatChunkWriter {
    private final OutputStream out;
    private final ObjectMapper mapper;
    private final String completionId;
    private final Instant createdAt;
    private final ModelName model;

    public ChatChunkWriter(final OutputStream out, final ObjectMapper mapper, final String completionId, final Instant createdAt,
            final ModelName model) {
        this.out = out;
        this.mapper = mapper;
        this.completionId = completionId;
        this.createdAt = createdAt;
        this.model = model;
    }

    /** A chunk of completion text; the first one also names the assistant's role. */
    public void writeContent(final String content, final boolean first) throws IOException {
        final var chunk = newChunk();
        final var choice = chunk.putArray("choices").addObject();
        choice.put("index", 0);
        final var delta = choice.putObject("delta");
        if (first) {
            delta.put("role", "assistant");
        }
        delta.put("content", content);
        choice.putNull("finish_reason");
        writeEvent(chunk);
    }

    /** The chunk that ends the completion, and why it ended. */
    public void writeFinish(final String finishReason) throws IOException {
        final var chunk = newChunk();
        final var choice = chunk.putArray("choices").addObject();
        choice.put("index", 0);
        choice.putObject("delta");
        choice.put("finish_reason", finishReason);
        writeEvent(chunk);
    }

    /** The usage-only chunk, which a client gets when it asks for usage. */
    public void writeUsage(final long promptTokens, final long cachedTokens, final long completionTokens) throws IOException {
        final var chunk = newChunk();
        chunk.putArray("choices");
        final var usage = chunk.putObject("usage");
        usage.put("prompt_tokens", promptTokens).put("completion_tokens", completionTokens).put("total_tokens", promptTokens + completionTokens);
        usage.putObject("prompt_tokens_details").put("cached_tokens", cachedTokens);
        writeEvent(chunk);
    }

    /** The event that ends the stream. */
    public void writeDone() throws IOException {
        out.write("data: [DONE]\n\n".getBytes(UTF_8));
        out.flush();
    }

    private ObjectNode newChunk() {
        final var chunk = mapper.createObjectNode();
        chunk.put("id", completionId).put("object", "chat.completion.chunk").put("created", createdAt.getEpochSecond()).put("model", model.value());
        return chunk;
    }

    private void writeEvent(final ObjectNode chunk) throws IOException {
        out.write(("data: " + chunk + "\n\n").getBytes(UTF_8));
        out.flush();
    }
}
