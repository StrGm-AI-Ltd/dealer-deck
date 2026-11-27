package ai.strgm.util;

import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Null handling for values that may be absent. */
public final class Nulls {
    private Nulls() {
    }

    /**
     * Applies {@code mapper} to {@code value}, or returns null when {@code value} is null:
     * {@code mapNullable(heldLease, HeldLease::id)} instead of {@code heldLease == null ? null : heldLease.id()}. The value
     * is read once, and the lambda only ever sees a non-null value.
     */
    public static <T, R> @Nullable R mapNullable(final @Nullable T value, final Function<? super T, ? extends @Nullable R> mapper) {
        return value == null ? null : mapper.apply(value);
    }
}
