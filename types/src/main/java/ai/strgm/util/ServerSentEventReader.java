package ai.strgm.util;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Reads a stream of server-sent events ({@code text/event-stream}) one event at a time: the one place that knows how
 * the stream is framed, for every client that reads one. Close it to close the stream.
 */
public final class ServerSentEventReader implements AutoCloseable {
    /** The prefix of an event's data line. */
    private static final String DATA_LINE_PREFIX = "data:";

    private final BufferedReader reader;

    public ServerSentEventReader(final InputStream stream) {
        this.reader = new BufferedReader(new InputStreamReader(stream, UTF_8));
    }

    /** The next event, or null once the stream has ended. */
    public @Nullable ServerSentEvent nextEvent() throws IOException {
        final var text = new StringBuilder();
        final List<String> data = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            text.append(line).append('\n');
            if (line.isEmpty()) {
                return new ServerSentEvent(text.toString(), List.copyOf(data));
            }
            if (line.startsWith(DATA_LINE_PREFIX)) {
                data.add(line.substring(DATA_LINE_PREFIX.length()).trim());
            }
        }
        return text.isEmpty() ? null : new ServerSentEvent(text.toString(), List.copyOf(data));
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
