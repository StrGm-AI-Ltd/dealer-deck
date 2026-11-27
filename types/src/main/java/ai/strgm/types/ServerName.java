package ai.strgm.types;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;

/** A DECK server's name. For example, {@code stub}. */
public record ServerName(String value) implements MicroType<String> {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ServerName {
        requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }
}
