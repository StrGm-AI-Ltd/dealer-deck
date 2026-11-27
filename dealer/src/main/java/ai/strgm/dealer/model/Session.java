package ai.strgm.dealer.model;

import static ai.strgm.util.Nulls.mapNullable;
import static java.lang.Math.max;
import static java.lang.System.currentTimeMillis;
import static java.util.Objects.requireNonNull;

import ai.strgm.dealer.config.DealerProperties.ClientConfig;
import ai.strgm.dealer.api.model.SessionLeaseState;
import ai.strgm.dealer.model.DeckProfile.TokenPrices;
import ai.strgm.deck.api.model.LeaseState;
import ai.strgm.types.LeaseId;
import ai.strgm.types.ModelName;
import ai.strgm.types.SessionId;
import ai.strgm.types.SessionRef;
import org.jspecify.annotations.Nullable;

/**
 * An agent session as DEALER's scheduler tracks it: the lease it holds, its requests in flight and its usage. A
 * session holds one lease and queues one lease request at a time (#64). Not thread-safe: the scheduler calls it
 * under its lock.
 */
public final class Session {
    /** Prices are per million tokens. */
    private static final double TOKENS_PER_MILLION = 1_000_000.0;

    /** The lease a session holds; its profile and id are set and cleared together. */
    public record HeldLease(ModelProfileRef profileRef, LeaseId id) {}

    private final ClientConfig clientConfig;
    private final SessionId id;
    /** The model of the session's lease, or of its last one. */
    private @Nullable ModelName modelName;
    private @Nullable HeldLease heldLease;
    private SessionLeaseState leaseState = SessionLeaseState.NONE;
    private long leaseReadyAt;
    private long lastActivity = currentTimeMillis();
    private int inFlight;
    private boolean evictPending;
    private @Nullable LeaseRequest pendingLeaseRequest;
    private int requests;
    private long promptTokens;
    private long cachedTokens;
    private long completionTokens;
    // TODO(#104): a money amount with its currency, instead of a bare double whose currency only its name gives
    private double costUsd;

    public Session(final ClientConfig clientConfig, final SessionId id) {
        this.clientConfig = clientConfig;
        this.id = id;
    }

    public ClientConfig clientConfig() {
        return clientConfig;
    }

    public SessionId id() {
        return id;
    }

    /** The reference DECK knows the session by. */
    public SessionRef ref() {
        return SessionRef.of(clientConfig.name(), id);
    }

    /** Counts a new request, and restarts the session's idle time. */
    public void recordRequest() {
        requests++;
        lastActivity = currentTimeMillis();
    }

    /** The session's queued lease request, if any. */
    public @Nullable LeaseRequest pendingLeaseRequest() {
        return pendingLeaseRequest;
    }

    public void queue(final LeaseRequest leaseRequest) {
        pendingLeaseRequest = leaseRequest;
    }

    /** Forgets a queued lease request that was cancelled; a newer one stays. */
    public void dequeue(final LeaseRequest leaseRequest) {
        if (pendingLeaseRequest == leaseRequest) {
            pendingLeaseRequest = null;
        }
    }

    /** Whether a request for {@code modelName} can use the session's lease: it holds one for that model, not being evicted. */
    public boolean canReuse(final ModelName modelName) {
        return heldLease != null && !evictPending && modelName.equals(this.modelName);
    }

    /** Places another request on the session's lease, which {@link #canReuse} allowed. */
    public Placement reuse() {
        requireNonNull(heldLease, "canReuse() found a lease");
        inFlight++;
        return new Placement(this, heldLease.profileRef(), heldLease.id());
    }

    public boolean holdsLease() {
        return heldLease != null;
    }

    /** Whether the session holds lease {@code leaseId} on {@code serverRef}. */
    public boolean holds(final LeaseId leaseId, final DeckServerRef serverRef) {
        return heldLease != null && heldLease.id().equals(leaseId) && heldLease.profileRef().serverRef() == serverRef;
    }

    public @Nullable HeldLease heldLease() {
        return heldLease;
    }

    /** Places the session's queued lease request on a lease DECK has just granted. */
    public Placement place(final LeaseRequest leaseRequest, final ModelProfileRef profileRef, final DeckLease grantedLease) {
        modelName = leaseRequest.modelName();
        heldLease = new HeldLease(profileRef, grantedLease.id());
        if (grantedLease.state() == LeaseState.READY) {
            markReady(grantedLease);
        } else {
            leaseState = SessionLeaseState.fromValue(grantedLease.state().getValue());
        }
        inFlight++;
        pendingLeaseRequest = null;
        return new Placement(this, profileRef, grantedLease.id());
    }

    public boolean isReady() {
        return leaseState == SessionLeaseState.READY;
    }

    public void markReady(final DeckLease readyLease) {
        leaseState = SessionLeaseState.READY;
        leaseReadyAt = readyLease.readyAtMs() == null ? currentTimeMillis() : readyLease.readyAtMs();
    }

    /** Ends a request on the session's lease: undoes its in-flight count and adds its usage. */
    public void endRequest(final long promptTokens, final long cachedTokens, final long completionTokens, final TokenPrices prices) {
        inFlight = max(0, inFlight - 1);
        lastActivity = currentTimeMillis();
        this.promptTokens += promptTokens;
        this.cachedTokens += cachedTokens;
        this.completionTokens += completionTokens;
        costUsd += ((promptTokens - cachedTokens) * prices.inputPerMTok() + cachedTokens * prices.cachedInputPerMTok()
                + completionTokens * prices.outputPerMTok()) / TOKENS_PER_MILLION;
    }

    public int inFlight() {
        return inFlight;
    }

    /** Whether the session's lease is to be evicted and its last request has ended. */
    public boolean evictionDue() {
        return evictPending && inFlight == 0;
    }

    /**
     * Marks the session's lease for eviction, unless it's marked already or hasn't been ready for the minimum
     * lease time. Returns whether it was marked.
     */
    public boolean markEvicting(final long now, final long minLeaseMs) {
        if (evictPending || leaseReadyAt + minLeaseMs > now) {
            return false;
        }
        evictPending = true;
        leaseState = SessionLeaseState.EVICTING;
        return true;
    }

    /** Clears the session's lease and returns it, for the caller to evict on DECK. */
    public @Nullable HeldLease evicted() {
        return clearLease(SessionLeaseState.EVICTED);
    }

    /**
     * For a model switch: clears the session's lease once its requests have ended, and returns it for the caller to
     * release on DECK, so the session never holds two leases (#64). Null while it has requests in flight.
     */
    public @Nullable HeldLease releasedForSwitch() {
        return inFlight == 0 ? clearLease(SessionLeaseState.RELEASED) : null;
    }

    /** Clears the session's lease if it has been idle for longer than {@code idleMs}, and returns it, for release on DECK. */
    public @Nullable HeldLease releasedIfIdle(final long now, final long idleMs) {
        return inFlight == 0 && !evictPending && now - lastActivity > idleMs ? clearLease(SessionLeaseState.RELEASED_IDLE) : null;
    }

    private @Nullable HeldLease clearLease(final SessionLeaseState state) {
        final var clearedLease = heldLease;
        if (clearedLease != null) {
            heldLease = null;
            evictPending = false;
            leaseState = state;
        }
        return clearedLease;
    }

    /** The session's state at {@code now}, for the scheduler's report. */
    public SessionSnapshot snapshot(final long now) {
        return new SessionSnapshot(clientConfig.name(), id, modelName,
                mapNullable(heldLease, currentLease -> currentLease.profileRef().serverRef().name()),
                mapNullable(heldLease, currentLease -> currentLease.profileRef().serverRef().config().egress()),
                mapNullable(heldLease, currentLease -> currentLease.profileRef().profile().id()),
                mapNullable(heldLease, HeldLease::id),
                leaseState, heldLease == null || leaseReadyAt == 0 ? null : now - leaseReadyAt, inFlight > 0 ? 0L : now - lastActivity,
                inFlight, evictPending, requests, promptTokens, cachedTokens, completionTokens, costUsd);
    }
}
