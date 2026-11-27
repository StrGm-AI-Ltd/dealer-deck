package ai.strgm.types;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * An agent session's id, as a client sends it in {@code X-Session-Id}; unique within the client. Not blank, and at most
 * {@value #MAX_LENGTH} characters: creating one otherwise throws. For example, {@code a-1}.
 */
public record SessionId(String value) implements MicroType<String> {
    public static final int MAX_LENGTH = 200;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public SessionId {
        requireNonNull(value, "value");
        if (value.isBlank() || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("A session id must not be blank, and must have at most " + MAX_LENGTH + " characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
