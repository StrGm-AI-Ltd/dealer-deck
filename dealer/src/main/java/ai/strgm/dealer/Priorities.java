package ai.strgm.dealer;

import org.apache.commons.lang3.Range;

/** The range of client priorities and request priorities: higher goes first, and only client priority evicts. */
final class Priorities {
    static final int MIN = 1;
    static final int MAX = 100;
    static final Range<Integer> RANGE = Range.of(MIN, MAX);
    /** A request's priority when it doesn't send {@code X-Request-Priority}. */
    static final int DEFAULT_REQUEST = 50;

    private Priorities() {
    }
}
