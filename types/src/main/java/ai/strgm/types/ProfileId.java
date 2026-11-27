package ai.strgm.types;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;

/** A model profile's id, unique on its DECK server. For example, {@code small-a}. */
public record ProfileId(String value) implements MicroType<String> {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ProfileId {
        requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }
}
