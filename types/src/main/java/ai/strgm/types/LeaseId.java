package ai.strgm.types;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Objects;

/** A lease's id, as DECK grants it. For example, {@code lease-24af55a1}. */
public record LeaseId(String value) implements MicroType<String> {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public LeaseId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }
}
