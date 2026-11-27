package ai.strgm.types;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;

/** An agent session's id, as a client sends it in {@code X-Session-Id}; unique within the client. For example, {@code a-1}. */
public record SessionId(String value) implements MicroType<String> {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public SessionId {
        requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }
}
