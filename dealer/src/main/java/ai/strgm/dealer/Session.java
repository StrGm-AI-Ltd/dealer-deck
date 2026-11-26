package ai.strgm.dealer;

import ai.strgm.dealer.DealerProperties.ClientDef;
import ai.strgm.dealer.DeckRegistry.ProfileRef;
import ai.strgm.dealer.DeckRegistry.Server;
import ai.strgm.dealer.Scheduler.Placement;
import ai.strgm.dealer.api.model.SessionInfo;
import ai.strgm.dealer.api.model.SessionLeaseState;
import ai.strgm.deck.api.model.Lease;
import ai.strgm.deck.api.model.LeaseState;
import ai.strgm.deck.api.model.Prices;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An agent session as DEALER's scheduler tracks it: the lease it holds, its requests in flight and its usage. A
 * session holds one lease and queues one lease request at a time (#64). Not thread-safe: the scheduler calls it
 * under its lock.
 */
final class Session {
    /** Prices are per million tokens. */
    private static final double TOKENS_PER_MILLION = 1_000_000.0;

    /** The lease a session holds; its profile and id are set and cleared together. */
    record HeldLease(ProfileRef profile, String id) {}

    private final ClientDef client;
    private final String id;
    /** The model of the session's lease, or of its last one. */
    private @Nullable String model;
    private @Nullable HeldLease lease;
    private SessionLeaseState leaseState = SessionLeaseState.NONE;
    private long leaseReadyAt;
    private long lastActivity = System.currentTimeMillis();
    private int inFlight;
    private boolean evictPending;
    private @Nullable LeaseRequest pending;
    private int requests;
    private long promptTokens;
    private long cachedTokens;
    private long completionTokens;
    // TODO(#104): a money amount with its currency, instead of a bare double whose currency only its name gives
    private double costUsd;

    Session(final ClientDef client, final String id) {
        this.client = client;
        this.id = id;
    }

    ClientDef client() {
        return client;
    }

    String id() {
        return id;
    }

    /** The reference DECK knows the session by: {@code <client>/<session id>}. */
    String ref() {
        return client.name() + "/" + id;
    }

    /** Counts a new request, and restarts the session's idle time. */
    void recordRequest() {
        requests++;
        lastActivity = System.currentTimeMillis();
    }

    /** The session's queued lease request, if any. */
    @Nullable LeaseRequest pending() {
        return pending;
    }

    void queue(final LeaseRequest leaseRequest) {
        pending = leaseRequest;
    }

    /** Forgets a queued lease request that was cancelled; a newer one stays. */
    void dequeue(final LeaseRequest leaseRequest) {
        if (pending == leaseRequest) {
            pending = null;
        }
    }

    /** Whether a request for {@code model} can use the session's lease: it holds one for that model, not being evicted. */
    boolean canReuse(final String model) {
        return lease != null && !evictPending && model.equals(this.model);
    }

    /** Places another request on the session's lease, which {@link #canReuse} allowed. */
    Placement reuse() {
        final var held = Objects.requireNonNull(lease, "canReuse() found a lease");
        inFlight++;
        return new Placement(this, held.profile(), held.id());
    }

    boolean holdsLease() {
        return lease != null;
    }

    /** Whether the session holds lease {@code leaseId} on {@code server}. */
    boolean holds(final String leaseId, final Server server) {
        return lease != null && lease.id().equals(leaseId) && lease.profile().server() == server;
    }

    @Nullable HeldLease lease() {
        return lease;
    }

    /** Places the session's queued lease request on a lease DECK has just granted. */
    Placement place(final LeaseRequest leaseRequest, final ProfileRef profile, final Lease granted) {
        model = leaseRequest.model();
        lease = new HeldLease(profile, granted.getId());
        if (granted.getState() == LeaseState.READY) {
            markReady(granted);
        } else {
            leaseState = SessionLeaseState.fromValue(granted.getState().getValue());
        }
        inFlight++;
        pending = null;
        return new Placement(this, profile, granted.getId());
    }

    boolean isReady() {
        return leaseState == SessionLeaseState.READY;
    }

    void markReady(final Lease ready) {
        leaseState = SessionLeaseState.READY;
        leaseReadyAt = ready.getReadyAtMs() == null ? System.currentTimeMillis() : ready.getReadyAtMs();
    }

    /** Ends a request on the session's lease: undoes its in-flight count and adds its usage. */
    void endRequest(final long prompt, final long cached, final long completion, final @Nullable Prices prices) {
        inFlight = Math.max(0, inFlight - 1);
        lastActivity = System.currentTimeMillis();
        promptTokens += prompt;
        cachedTokens += cached;
        completionTokens += completion;
        if (prices != null) {
            costUsd += ((prompt - cached) * orZero(prices.getInputPerMTok()) + cached * orZero(prices.getCachedInputPerMTok())
                    + completion * orZero(prices.getOutputPerMTok())) / TOKENS_PER_MILLION;
        }
    }

    private static double orZero(final @Nullable Double price) {
        return price == null ? 0 : price;
    }

    int inFlight() {
        return inFlight;
    }

    /** Whether the session's lease is to be evicted and its last request has ended. */
    boolean evictionDue() {
        return evictPending && inFlight == 0;
    }

    /**
     * Marks the session's lease for eviction, unless it's marked already or hasn't been ready for the minimum
     * lease time. Returns whether it was marked.
     */
    boolean markEvicting(final long now, final long minLeaseMs) {
        if (evictPending || leaseReadyAt + minLeaseMs > now) {
            return false;
        }
        evictPending = true;
        leaseState = SessionLeaseState.EVICTING;
        return true;
    }

    /** Clears the session's lease and returns it, for the caller to evict on DECK. */
    @Nullable HeldLease evicted() {
        return clearLease(SessionLeaseState.EVICTED);
    }

    /**
     * For a model switch: clears the session's lease once its requests have ended, and returns it for the caller to
     * release on DECK, so the session never holds two leases (#64). Null while it has requests in flight.
     */
    @Nullable HeldLease releasedForSwitch() {
        return inFlight == 0 ? clearLease(SessionLeaseState.RELEASED) : null;
    }

    /** Clears the session's lease if it has been idle for longer than {@code idleMs}, and returns it, for release on DECK. */
    @Nullable HeldLease releasedIfIdle(final long now, final long idleMs) {
        return inFlight == 0 && !evictPending && now - lastActivity > idleMs ? clearLease(SessionLeaseState.RELEASED_IDLE) : null;
    }

    private @Nullable HeldLease clearLease(final SessionLeaseState state) {
        final var held = lease;
        if (held != null) {
            lease = null;
            evictPending = false;
            leaseState = state;
        }
        return held;
    }

    SessionInfo toInfo(final long now) {
        return new SessionInfo().client(client.name()).sessionId(id).model(model)
                .deckServer(lease == null ? null : lease.profile().server().name())
                .serverType(lease == null ? null : lease.profile().server().def().egress())
                .profileId(lease == null ? null : lease.profile().profile().getId()).leaseId(lease == null ? null : lease.id())
                .leaseState(leaseState).leaseAgeMs(lease == null || leaseReadyAt == 0 ? null : now - leaseReadyAt)
                .idleMs(inFlight > 0 ? 0L : now - lastActivity).inFlight(inFlight).evictPending(evictPending)
                .requests(requests).promptTokens(promptTokens).cachedTokens(cachedTokens)
                .completionTokens(completionTokens).costUsd(costUsd);
    }
}
