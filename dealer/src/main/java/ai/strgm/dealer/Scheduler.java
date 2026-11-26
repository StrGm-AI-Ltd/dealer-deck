package ai.strgm.dealer;

import ai.strgm.dealer.DealerProperties.ClientDef;
import ai.strgm.dealer.DeckRegistry.ProfileRef;
import ai.strgm.dealer.api.model.EvictionEvent;
import ai.strgm.dealer.api.model.QueuedRequest;
import ai.strgm.dealer.api.model.SessionInfo;
import ai.strgm.deck.api.model.LeaseState;
import ai.strgm.deck.api.model.ModelProfile;
import ai.strgm.types.LeaseId;
import ai.strgm.types.ModelName;
import ai.strgm.types.ProfileId;
import ai.strgm.types.SessionId;
import ai.strgm.types.SessionRef;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The router queue, placement and eviction.
 * <ul>
 * <li>A session keeps its lease across requests (sticky) until the lease idles out or is evicted.</li>
 * <li>A session holds one lease and queues one acquisition at a time: its other requests wait for the queued one,
 * and a model switch waits for the requests in flight, so the old lease is released before the new one is acquired
 * (#64).</li>
 * <li>Dispatch places every placeable request in priority order: a request blocked on one server doesn't
 * hold up one that another server can take (D97).</li>
 * <li>Only client priority evicts (D59), between requests (D31), and not before the minimum lease time M.
 * Capacity being drained for a lease request is reserved for it, so a victim can't re-take it (D97).</li>
 * <li>{@code auto-*} sessions are placed afresh when they have no lease: local servers first, external ones
 * only for clients that allow it (D79, D96).</li>
 * </ul>
 */
@Component
public class Scheduler {
    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);
    private static final Pattern AUTO = Pattern.compile("auto-(\\d+)k");
    /** An {@code auto-<N>k} model that isn't one of the {@link AutoTier}s needs N thousand tokens. */
    private static final int TOKENS_PER_K = 1000;
    /** How long a request waits for its lease to become ready. */
    private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(90);
    private static final long READINESS_POLL_MS = 150;
    /** How many eviction events {@code /metrics/state} keeps. */
    private static final int MAX_EVICTION_EVENTS = 50;
    private static final long TICK_INTERVAL_MS = 500;

    public record Placement(Session session, ProfileRef profile, LeaseId leaseId) {}

    private final DeckRegistry decks;
    private final DealerProperties properties;
    private final Object lock = new Object();
    private final Map<SessionRef, Session> sessions = new LinkedHashMap<>();
    private final List<LeaseRequest> leaseRequests = new ArrayList<>();
    private final Map<String, LeaseRequest> reservations = new HashMap<>();
    private final Deque<EvictionEvent> evictions = new ArrayDeque<>();
    private long nextArrivalOrder;

    public Scheduler(final DeckRegistry decks, final DealerProperties properties) {
        this.decks = decks;
        this.properties = properties;
    }

    /** The profiles a request may use, in preference order. */
    List<ProfileRef> candidates(final ClientDef client, final ModelName model) {
        final var all = decks.profiles();
        final var auto = AUTO.matcher(model.value());
        if (auto.matches()) {
            final int min = AutoTier.ALL.stream().filter(tier -> tier.name().equals(model)).mapToInt(AutoTier::contextWindow)
                    .findFirst().orElse(Integer.parseInt(auto.group(1)) * TOKENS_PER_K);
            final var local = all.stream().filter(profileRef -> !profileRef.external() && profileRef.profile().getContextWindow() >= min)
                    .sorted(Comparator.comparingInt(profileRef -> profileRef.profile().getContextWindow())).toList();
            final var external = client.externalRouting()
                    ? all.stream().filter(profileRef -> profileRef.external() && profileRef.profile().getContextWindow() >= min).toList()
                    : List.<ProfileRef>of();
            return Stream.concat(local.stream(), external.stream()).toList();
        }
        // a client may name a profile by its id as well as by its model
        return all.stream().filter(profileRef -> profileRef.profile().getId().value().equals(model.value()) || profileRef.profile().getModel().equals(model))
                .filter(profileRef -> !profileRef.external() || client.externalRouting()).toList();
    }

    public boolean isVirtual(final ModelName model) {
        return AUTO.matcher(model.value()).matches();
    }

    /** Blocks until the request is placed on a ready lease. */
    public Placement acquire(final ClientDef client, final SessionId sessionId, final ModelName model, final int requestPriority) {
        final var placement = place(client, sessionId, model, requestPriority);
        try {
            awaitReady(placement);
        } catch (RuntimeException exception) {
            // the request won't be served: undo its in-flight count, so the lease can still idle out or be evicted,
            // and keep the lease, so a retry can use it (#101)
            release(placement, 0, 0, 0);
            throw exception;
        }
        return placement;
    }

    /**
     * Places the request on its session's lease, or queues it for a new one. A session queues one acquisition at a
     * time (#64): a request that finds one queued waits for it, then tries again.
     */
    private Placement place(final ClientDef client, final SessionId sessionId, final ModelName model, final int requestPriority) {
        final Session session;
        synchronized (lock) {
            session = sessions.computeIfAbsent(sessionKey(client, sessionId), key -> new Session(client, sessionId));
            session.recordRequest();
        }
        while (true) {
            final LeaseRequest leaseRequest;
            final boolean queuedAhead;
            synchronized (lock) {
                if (session.pending() != null) {
                    leaseRequest = session.pending();
                    queuedAhead = true;
                } else if (session.canReuse(model)) {
                    return session.reuse();
                } else {
                    leaseRequest = new LeaseRequest(session, model, requestPriority, nextArrivalOrder++);
                    leaseRequests.add(leaseRequest);
                    session.queue(leaseRequest);
                    queuedAhead = false;
                }
            }
            if (!queuedAhead) {
                dispatch();
                return placed(leaseRequest);
            }
            awaitSettled(leaseRequest);
        }
    }

    /**
     * A session's key: the reference DECK knows it by. Session ids are scoped to their client, and two clients can
     * both have a session {@code s-1}, so the key holds both.
     */
    private static SessionRef sessionKey(final ClientDef client, final SessionId sessionId) {
        return SessionRef.of(client.name(), sessionId);
    }

    /** Waits for the request's own acquisition to be placed. */
    private Placement placed(final LeaseRequest leaseRequest) {
        try {
            return leaseRequest.placement().get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancel(leaseRequest);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "server_error", "Interrupted while queued");
        } catch (ExecutionException exception) {
            final var cause = exception.getCause();
            final var reason = cause == null ? null : cause.getMessage();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "server_error", reason != null ? reason : "The request couldn't be placed");
        }
    }

    /** Waits, without holding the lock, for another request's acquisition in the session to be placed or cancelled. */
    private static void awaitSettled(final LeaseRequest leaseRequest) {
        try {
            leaseRequest.placement().get();
        } catch (ExecutionException exception) {
            // it was cancelled: the next attempt decides what this request does
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "server_error", "Interrupted while queued");
        }
    }

    private void cancel(final LeaseRequest leaseRequest) {
        synchronized (lock) {
            leaseRequests.remove(leaseRequest);
            reservations.values().removeIf(reserved -> reserved == leaseRequest);
            leaseRequest.session().dequeue(leaseRequest);
            // wakes the requests waiting for it; a placement made just before the cancel is undone
            if (!leaseRequest.placement().completeExceptionally(new IllegalStateException("The queued request was cancelled"))) {
                release(leaseRequest.placement().join(), 0, 0, 0);
            }
        }
    }

    /** Waits for the placement's lease to be ready, asking DECK until the session knows it is. */
    private void awaitReady(final Placement placement) {
        final var session = placement.session();
        final var deadline = System.currentTimeMillis() + READINESS_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            synchronized (lock) {
                if (session.isReady()) {
                    return;
                }
            }
            final var lease = decks.lease(placement.profile().server(), placement.leaseId());
            if (lease.getState() == LeaseState.READY) {
                synchronized (lock) {
                    session.markReady(lease);
                }
                return;
            }
            sleep(READINESS_POLL_MS);
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "server_error", "Lease " + placement.leaseId() + " never became ready");
    }

    /** Called when a request's stream ends: accounts usage and applies a pending eviction. */
    public void release(final Placement placement, final long prompt, final long cached, final long completion) {
        synchronized (lock) {
            final var session = placement.session();
            session.endRequest(prompt, cached, completion, placement.profile().profile().getPrices());
            if (session.evictionDue()) {
                evictNow(session);
            }
        }
        dispatch();
    }

    private void evictNow(final Session session) {
        final var evicted = session.evicted();
        if (evicted != null) {
            decks.evict(evicted.profile().server(), evicted.id());
            log.info("Evicted {} from {}", session.ref(), evicted.profile().key());
        }
    }

    private boolean reservedForOther(final ProfileRef candidate, final LeaseRequest leaseRequest) {
        for (final var reservation : reservations.entrySet()) {
            if (reservation.getValue() == leaseRequest) {
                continue;
            }
            final var reserved = decks.profiles().stream().filter(profileRef -> profileRef.key().equals(reservation.getKey())).findFirst();
            if (reserved.isEmpty()) {
                continue;
            }
            final var reservedProfile = reserved.get();
            if (reservedProfile.key().equals(candidate.key())) {
                return true;
            }
            if (reservedProfile.server() == candidate.server() && (reduces(reservedProfile.profile(), candidate.profile().getId()) || reduces(candidate.profile(), reservedProfile.profile().getId()))) {
                return true;
            }
        }
        return false;
    }

    /** Whether a lease on {@code p} reduces the leases another profile on its server can grant. Any reduction counts. */
    private static boolean reduces(final ModelProfile profile, final ProfileId otherId) {
        return profile.getLimitations() != null && profile.getLimitations().getLeaseReductions() != null
                && profile.getLimitations().getLeaseReductions().stream().anyMatch(reduction -> reduction.getProfileId().equals(otherId));
    }

    /** Places every placeable lease request, in priority order, and starts evictions where client priority allows. */
    public void dispatch() {
        synchronized (lock) {
            // A lease request whose session still holds a lease is a model switch, or a request that came while the
            // lease was being evicted. Once the session's requests in flight end, the old lease goes first: released
            // here for a model switch, evicted by release() for an eviction. So a session never holds two leases (#64).
            for (final var leaseRequest : leaseRequests) {
                final var released = leaseRequest.session().releasedForSwitch();
                if (released != null) {
                    decks.release(released.profile().server(), released.id());
                }
            }
            leaseRequests.sort(Comparator.<LeaseRequest>comparingInt(queued -> -queued.session().client().priority())
                    .thenComparingInt(queued -> -queued.requestPriority()).thenComparingLong(LeaseRequest::arrivalOrder));
            final var queue = leaseRequests.iterator();
            while (queue.hasNext()) {
                final var leaseRequest = queue.next();
                final var session = leaseRequest.session();
                if (session.holdsLease()) {
                    continue;
                }
                ProfileRef firstBlocked = null;
                List<LeaseId> firstBlockers = null;
                boolean placed = false;
                for (final var candidate : candidates(session.client(), leaseRequest.model())) {
                    if (reservedForOther(candidate, leaseRequest)) {
                        continue;
                    }
                    try {
                        final var lease = decks.acquire(candidate, session.ref());
                        final var placement = session.place(leaseRequest, candidate, lease);
                        queue.remove();
                        reservations.values().removeIf(reserved -> reserved == leaseRequest);
                        leaseRequest.placement().complete(placement);
                        log.info("Placed {} on {} ({})", session.ref(), candidate.key(), lease.getState().getValue());
                        placed = true;
                        break;
                    } catch (DeckRegistry.Blocked blocked) {
                        if (firstBlocked == null) {
                            firstBlocked = candidate;
                            firstBlockers = blocked.blockers;
                        }
                    } catch (Exception exception) {
                        log.warn("Lease on {} failed: {}", candidate.key(), exception.getMessage());
                    }
                }
                if (!placed && firstBlocked != null && firstBlockers != null) {
                    considerEviction(leaseRequest, firstBlocked, firstBlockers);
                }
            }
        }
    }

    private void considerEviction(final LeaseRequest leaseRequest, final ProfileRef target, final List<LeaseId> blockers) {
        final List<Session> victims = new ArrayList<>();
        for (final var leaseId : blockers) {
            final var holder = sessions.values().stream().filter(session -> session.holds(leaseId, target.server())).findFirst();
            if (holder.isEmpty() || holder.get().client().priority() >= leaseRequest.session().client().priority()) {
                return;
            }
            victims.add(holder.get());
        }
        reservations.putIfAbsent(target.key(), leaseRequest);
        final var now = System.currentTimeMillis();
        final var minLeaseMs = Duration.ofSeconds(properties.timers().minLeaseSeconds()).toMillis();
        for (final var victim : victims) {
            if (!victim.markEvicting(now, minLeaseMs)) {
                continue;
            }
            final var victimLease = victim.lease();
            evictions.addLast(new EvictionEvent().createdAt(Instant.ofEpochMilli(now).getEpochSecond()).victimClient(victim.client().name()).victimSession(victim.id())
                    .deckServer(target.server().name()).profileId(victimLease == null ? null : victimLease.profile().profile().getId()).byClient(leaseRequest.session().client().name())
                    .reason("priority " + leaseRequest.session().client().priority() + " > " + victim.client().priority() + " needs " + target.profile().getId()));
            while (evictions.size() > MAX_EVICTION_EVENTS) {
                evictions.removeFirst();
            }
            log.info("Evicting {} for {} (needs {})", victim.ref(), leaseRequest.session().ref(), target.key());
            if (victim.inFlight() == 0) {
                evictNow(victim);
            }
        }
    }

    /** Releases leases idle for longer than N, then re-dispatches (evictions wait for M to pass). */
    @Scheduled(fixedDelay = TICK_INTERVAL_MS)
    public void tick() {
        synchronized (lock) {
            final var idleMs = Duration.ofSeconds(properties.timers().idleSeconds()).toMillis();
            final var now = System.currentTimeMillis();
            for (final var session : sessions.values()) {
                final var released = session.releasedIfIdle(now, idleMs);
                if (released != null) {
                    decks.release(released.profile().server(), released.id());
                    log.info("Released idle lease of {} on {}", session.ref(), released.profile().key());
                }
            }
        }
        dispatch();
    }

    public List<QueuedRequest> queue() {
        synchronized (lock) {
            final var now = System.currentTimeMillis();
            return leaseRequests.stream().map(leaseRequest -> new QueuedRequest().client(leaseRequest.session().client().name())
                    .sessionId(leaseRequest.session().id()).model(leaseRequest.model()).clientPriority(leaseRequest.session().client().priority())
                    .requestPriority(leaseRequest.requestPriority()).waitingMs(now - leaseRequest.enqueuedAt()).reservedProfile(reservations.entrySet().stream()
                            .filter(reservation -> reservation.getValue() == leaseRequest).map(Map.Entry::getKey).findFirst().orElse(null)))
                    .toList();
        }
    }

    public List<SessionInfo> sessions() {
        synchronized (lock) {
            final var now = System.currentTimeMillis();
            return sessions.values().stream().map(session -> session.toInfo(now)).toList();
        }
    }

    public List<EvictionEvent> evictions() {
        synchronized (lock) {
            return List.copyOf(evictions);
        }
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
