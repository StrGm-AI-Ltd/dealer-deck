package ai.strgm.dealer;

import ai.strgm.dealer.Scheduler.Placement;
import java.util.concurrent.CompletableFuture;

/**
 * A session's request for a lease, queued until dispatch can place it. A session queues one at a time, and its
 * other requests wait for its {@code placement} (#64).
 *
 * @param arrivalOrder orders lease requests of equal priority by arrival
 * @param placement completed when dispatch places the request, or exceptionally when it's cancelled
 */
record LeaseRequest(Session session, String model, int requestPriority, long enqueuedAt, long arrivalOrder,
        CompletableFuture<Placement> placement) {

    LeaseRequest(final Session session, final String model, final int requestPriority, final long arrivalOrder) {
        this(session, model, requestPriority, System.currentTimeMillis(), arrivalOrder, new CompletableFuture<>());
    }
}
