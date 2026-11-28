package ai.strgm.util;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** How a stream of server-sent events is split into events, each kept as it was sent. */
class ServerSentEventReaderTest {
    private static ServerSentEventReader readerOf(final String stream) {
        return new ServerSentEventReader(new ByteArrayInputStream(stream.getBytes(UTF_8)));
    }

    @Test
    void eachEventKeepsItsTextAndDataUntilTheStreamEnds() throws IOException {
        try (var reader = readerOf("data: {\"a\":1}\n\n: a comment\ndata: [DONE]\n\n")) {
            assertEquals(new ServerSentEvent("data: {\"a\":1}\n\n", List.of("{\"a\":1}")), reader.nextEvent());
            assertEquals(new ServerSentEvent(": a comment\ndata: [DONE]\n\n", List.of("[DONE]")), reader.nextEvent());
            assertNull(reader.nextEvent());
        }
    }

    @Test
    void anUnendedLastEventAndAnExtraBlankLineArePassedOnAsTheyCame() throws IOException {
        try (var reader = readerOf("\ndata: x\r\n")) {
            assertEquals(new ServerSentEvent("\n", List.of()), reader.nextEvent());
            assertEquals(new ServerSentEvent("data: x\n", List.of("x")), reader.nextEvent());
            assertNull(reader.nextEvent());
        }
    }
}
