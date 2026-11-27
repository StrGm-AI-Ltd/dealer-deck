package ai.strgm.types;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;

/** The {@code model} a chat request names: a profile, a provider's model or a virtual {@code auto-*} model. For example, {@code auto-32k}. */
public record ModelName(String value) implements MicroType<String> {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ModelName {
        requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }
}
