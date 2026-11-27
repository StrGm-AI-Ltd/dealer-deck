package ai.strgm.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The microtypes' equality, which their records generate ({@link Record#equals(Object)} and {@link Record#hashCode()}):
 * two microtypes are equal when they're of the same type and hold the same value, never across types, and collections
 * match them by value. A hand-written {@code equals} or a second record component that broke this would fail here.
 * Different types are compared as {@code Object}s, which is how they meet at runtime; Error Prone's
 * {@code EqualsIncompatibleType} rejects such a comparison between static types at compile time.
 */
class MicroTypeEqualityTest {
    private static final String VALUE = "same-value";
    private static final String OTHER_VALUE = "other-value";
    private static final String MAPPED = "mapped";

    static Stream<Named<Function<String, MicroType<String>>>> microTypes() {
        return Stream.of(
                microType("LeaseId", LeaseId::new),
                microType("ProfileId", ProfileId::new),
                microType("SessionRef", SessionRef::new),
                microType("SessionId", SessionId::new),
                microType("ModelName", ModelName::new),
                microType("ClientName", ClientName::new),
                microType("ServerName", ServerName::new));
    }

    private static Named<Function<String, MicroType<String>>> microType(final String typeName,
            final Function<String, MicroType<String>> constructor) {
        return Named.of(typeName, constructor);
    }

    @ParameterizedTest
    @MethodSource("microTypes")
    void sameTypeAndValueAreEqual(final Function<String, MicroType<String>> constructor) {
        final var microType = constructor.apply(VALUE);
        final var sameValue = constructor.apply(VALUE);
        assertNotSame(microType, sameValue);
        assertEquals(microType, sameValue);
        assertEquals(sameValue, microType);
        assertEquals(microType.hashCode(), sameValue.hashCode());
    }

    @ParameterizedTest
    @MethodSource("microTypes")
    void differentValuesAreNotEqual(final Function<String, MicroType<String>> constructor) {
        assertNotEquals(constructor.apply(VALUE), constructor.apply(OTHER_VALUE));
    }

    @ParameterizedTest
    @MethodSource("microTypes")
    void aMicroTypeNeverEqualsItsPlainValue(final Function<String, MicroType<String>> constructor) {
        final Object microType = constructor.apply(VALUE);
        assertNotEquals(microType, VALUE);
        assertNotEquals(VALUE, microType);
    }

    @ParameterizedTest
    @MethodSource("microTypes")
    void collectionsMatchByValue(final Function<String, MicroType<String>> constructor) {
        final var microType = constructor.apply(VALUE);
        assertTrue(List.of(microType).contains(constructor.apply(VALUE)));
        assertEquals(1, new HashSet<>(List.of(microType, constructor.apply(VALUE))).size());
        final Map<MicroType<String>, String> byMicroType = new HashMap<>();
        byMicroType.put(microType, MAPPED);
        assertEquals(MAPPED, byMicroType.get(constructor.apply(VALUE)));
    }

    @Test
    void differentTypesWithTheSameValueAreNeverEqual() {
        final List<Object> microTypes = sameValueOfEveryType();
        for (final var microType : microTypes) {
            for (final var otherType : microTypes) {
                if (otherType != microType) {
                    assertNotEquals(microType, otherType, microType.getClass().getSimpleName() + " equals "
                            + otherType.getClass().getSimpleName());
                }
            }
        }
    }

    @Test
    void collectionsKeepDifferentTypesApart() {
        final List<Object> microTypes = sameValueOfEveryType();
        assertEquals(microTypes.size(), new HashSet<>(microTypes).size());
        final Map<Object, String> typeNames = new HashMap<>();
        microTypes.forEach(microType -> typeNames.put(microType, microType.getClass().getSimpleName()));
        assertEquals(microTypes.size(), typeNames.size());
        sameValueOfEveryType().forEach(microType -> assertEquals(microType.getClass().getSimpleName(), typeNames.get(microType)));
    }

    /** {@link #VALUE} as each microtype, typed as {@code Object}. */
    private static List<Object> sameValueOfEveryType() {
        return microTypes()
                .map(named -> (Object) named.getPayload().apply(VALUE))
                .toList();
    }
}
