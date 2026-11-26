package ai.strgm.types;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Objects;

/** A DECK server's name. For example, {@code stub}. */
public record ServerName(String value) implements MicroType<String> {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ServerName {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }
}
