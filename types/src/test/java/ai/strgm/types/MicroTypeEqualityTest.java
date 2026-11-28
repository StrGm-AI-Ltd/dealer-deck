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
    private static final int PRIORITY_VALUE = 50;
    private static final int OTHER_PRIORITY_VALUE = 80;
    private static final String MAPPED = "mapped";

    /** Two instances of one microtype that hold the same value, and one that holds another. */
    record Samples(MicroType<?> microType, MicroType<?> sameValue, MicroType<?> otherValue) {
    }

    static Stream<Named<Samples>> microTypes() {
        return Stream.of(
                samples("LeaseId", LeaseId::new, VALUE, OTHER_VALUE),
                samples("ProfileId", ProfileId::new, VALUE, OTHER_VALUE),
                samples("SessionRef", SessionRef::new, VALUE, OTHER_VALUE),
                samples("SessionId", SessionId::new, VALUE, OTHER_VALUE),
                samples("ModelName", ModelName::new, VALUE, OTHER_VALUE),
                samples("ClientName", ClientName::new, VALUE, OTHER_VALUE),
                samples("ServerName", ServerName::new, VALUE, OTHER_VALUE),
                samples("Priority", Priority::new, PRIORITY_VALUE, OTHER_PRIORITY_VALUE));
    }

    private static <T> Named<Samples> samples(final String typeName, final Function<T, MicroType<T>> constructor, final T value,
            final T otherValue) {
        return Named.of(typeName, new Samples(constructor.apply(value), constructor.apply(value), constructor.apply(otherValue)));
    }

    @ParameterizedTest
    @MethodSource("microTypes")
    void sameTypeAndValueAreEqual(final Samples samples) {
        assertNotSame(samples.microType(), samples.sameValue());
        assertEquals(samples.microType(), samples.sameValue());
        assertEquals(samples.sameValue(), samples.microType());
        assertEquals(samples.microType().hashCode(), samples.sameValue().hashCode());
    }

    @ParameterizedTest
    @MethodSource("microTypes")
    void differentValuesAreNotEqual(final Samples samples) {
        assertNotEquals(samples.microType(), samples.otherValue());
    }

    @ParameterizedTest
    @MethodSource("microTypes")
    void aMicroTypeNeverEqualsItsPlainValue(final Samples samples) {
        final Object microType = samples.microType();
        final Object plainValue = samples.microType().value();
        assertNotEquals(microType, plainValue);
        assertNotEquals(plainValue, microType);
    }

    @ParameterizedTest
    @MethodSource("microTypes")
    void collectionsMatchByValue(final Samples samples) {
        assertTrue(List.of(samples.microType()).contains(samples.sameValue()));
        assertEquals(1, new HashSet<>(List.of(samples.microType(), samples.sameValue())).size());
        final Map<MicroType<?>, String> byMicroType = new HashMap<>();
        byMicroType.put(samples.microType(), MAPPED);
        assertEquals(MAPPED, byMicroType.get(samples.sameValue()));
    }

    @Test
    void differentTypesWithTheSameValueAreNeverEqual() {
        final List<Object> microTypes = oneOfEveryType();
        microTypes.forEach(microType -> microTypes.stream()
                .filter(otherType -> otherType != microType)
                .forEach(otherType -> assertNotEquals(microType, otherType, microType.getClass().getSimpleName() + " equals "
                        + otherType.getClass().getSimpleName())));
    }

    @Test
    void collectionsKeepDifferentTypesApart() {
        final List<Object> microTypes = oneOfEveryType();
        assertEquals(microTypes.size(), new HashSet<>(microTypes).size());
        final Map<Object, String> typeNames = new HashMap<>();
        microTypes.forEach(microType -> typeNames.put(microType, microType.getClass().getSimpleName()));
        assertEquals(microTypes.size(), typeNames.size());
        oneOfEveryType().forEach(microType -> assertEquals(microType.getClass().getSimpleName(), typeNames.get(microType)));
    }

    /** One instance of each microtype, typed as {@code Object}; the string microtypes all hold {@link #VALUE}. */
    private static List<Object> oneOfEveryType() {
        return microTypes()
                .map(named -> (Object) named.getPayload().microType())
                .toList();
    }
}
