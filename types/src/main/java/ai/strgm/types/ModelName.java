package ai.strgm.types;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Objects;

/** The {@code model} a chat request names: a profile, a provider's model or a virtual {@code auto-*} model. For example, {@code auto-32k}. */
public record ModelName(String value) implements MicroType<String> {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ModelName {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }
}
