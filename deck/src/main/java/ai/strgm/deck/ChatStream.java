package ai.strgm.deck;

import java.io.IOException;
import java.io.OutputStream;

/** A completion streaming from a DECK server: writes its server-sent events to {@code out} until it ends. */
@FunctionalInterface
public interface ChatStream {
    void writeTo(OutputStream out) throws IOException;
}
