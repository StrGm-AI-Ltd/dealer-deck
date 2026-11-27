package ai.strgm.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** A session id's documented bounds: not blank, at most {@link SessionId#MAX_LENGTH} characters. */
class SessionIdTest {
    @Test
    void theLongestSessionIdIsValid() {
        final var longest = "s".repeat(SessionId.MAX_LENGTH);
        assertEquals(longest, new SessionId(longest).value());
    }

    @Test
    void aLongerSessionIdThrows() {
        assertThrows(IllegalArgumentException.class, () -> new SessionId("s".repeat(SessionId.MAX_LENGTH + 1)));
    }

    @Test
    void aBlankSessionIdThrows() {
        assertThrows(IllegalArgumentException.class, () -> new SessionId(""));
        assertThrows(IllegalArgumentException.class, () -> new SessionId("   "));
    }
}
