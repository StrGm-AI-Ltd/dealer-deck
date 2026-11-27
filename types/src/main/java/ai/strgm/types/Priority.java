package ai.strgm.types;

import static java.lang.Integer.parseInt;
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * A client's or a request's priority, from {@value #MIN} to {@value #MAX}: higher goes first, and only a client's
 * priority evicts. Creating one outside its bounds throws. For example, {@code 80}.
 */
public record Priority(Integer value) implements MicroType<Integer>, Comparable<Priority> {
    public static final int MIN = 1;
    public static final int MAX = 100;
    /** A request's priority when it doesn't send {@code X-Request-Priority}. */
    public static final Priority DEFAULT_REQUEST = new Priority(50);

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public Priority {
        requireNonNull(value, "value");
        if (value < MIN || value > MAX) {
            throw new IllegalArgumentException("A priority must be between " + MIN + " and " + MAX + ", not " + value);
        }
    }

    /** Parses a priority from its text, such as a configuration value; Spring's conversion finds it by name. */
    public static Priority valueOf(final String text) {
        return new Priority(parseInt(text));
    }

    @Override
    public int compareTo(final Priority other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
