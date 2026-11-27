package ai.strgm.dealer.model;

import ai.strgm.types.ModelName;
import ai.strgm.types.Priority;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * A session's request for a lease, queued until dispatch can place it. A session queues one at a time, and its
 * other requests wait for its {@code placement} (#64).
 *
 * @param arrivalOrder orders lease requests of equal priority by arrival
 * @param placement completed when dispatch places the request, or exceptionally when it's cancelled
 */
public record LeaseRequest(Session session, ModelName modelName, Priority requestPriority, Instant enqueuedAt, long arrivalOrder,
        CompletableFuture<Placement> placement) {

    public LeaseRequest(final Session session, final ModelName modelName, final Priority requestPriority, final long arrivalOrder) {
        this(session, modelName, requestPriority, Instant.now(), arrivalOrder, new CompletableFuture<>());
    }
}
