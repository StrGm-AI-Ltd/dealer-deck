package ai.strgm.types;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Objects;

/** An agent session's id, as a client sends it in {@code X-Session-Id}; unique within the client. For example, {@code a-1}. */
public record SessionId(String value) implements MicroType<String> {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public SessionId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }
}
