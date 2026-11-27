package ai.strgm.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** A priority's bounds, order, default and text. Its equality is in {@link MicroTypeEqualityTest}. */
class PriorityTest {
    /** The default the DEALER API documents for {@code X-Request-Priority}. */
    private static final int DOCUMENTED_DEFAULT_REQUEST_PRIORITY = 50;
    private static final int LOW = 20;
    private static final int HIGH = 80;

    @Test
    void theBoundsAreValidPriorities() {
        assertEquals(Priority.MIN, new Priority(Priority.MIN).value());
        assertEquals(Priority.MAX, new Priority(Priority.MAX).value());
    }

    @Test
    void creatingOneOutsideTheBoundsThrows() {
        assertThrows(IllegalArgumentException.class, () -> new Priority(Priority.MIN - 1));
        assertThrows(IllegalArgumentException.class, () -> new Priority(Priority.MAX + 1));
    }

    @Test
    void itParsesFromText() {
        assertEquals(new Priority(HIGH), Priority.valueOf(String.valueOf(HIGH)));
        assertThrows(IllegalArgumentException.class, () -> Priority.valueOf(String.valueOf(Priority.MAX + 1)));
        assertThrows(NumberFormatException.class, () -> Priority.valueOf("high"));
    }

    @Test
    void higherValuesSortLater() {
        final var sorted = Stream.of(new Priority(HIGH), new Priority(Priority.MIN), new Priority(LOW))
                .sorted()
                .toList();
        assertEquals(List.of(new Priority(Priority.MIN), new Priority(LOW), new Priority(HIGH)), sorted);
    }

    @Test
    void aRequestWithoutAPriorityGetsTheDocumentedDefault() {
        assertEquals(DOCUMENTED_DEFAULT_REQUEST_PRIORITY, Priority.DEFAULT_REQUEST.value());
    }

    @Test
    void itsTextIsItsValue() {
        assertEquals(String.valueOf(HIGH), new Priority(HIGH).toString());
    }
}
