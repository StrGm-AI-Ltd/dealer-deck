package ai.strgm.types;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;

/** A DEALER client's name, from DEALER's configuration. For example, {@code agent-a}. */
public record ClientName(String value) implements MicroType<String> {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ClientName {
        requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }
}
