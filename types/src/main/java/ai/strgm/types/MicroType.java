package ai.strgm.types;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A value of one specific kind, such as a lease id or a session id, with a type of its own, so the compiler rejects
 * one where another is expected. It serializes as its plain value, so a microtype changes no JSON, and its
 * {@code toString()} is that value too, for URLs, headers and logs.
 *
 * @param <T> the type of the plain value
 */
public interface MicroType<T> {
    @JsonValue
    T value();
}
