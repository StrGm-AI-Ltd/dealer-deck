package ai.strgm.dealer.service;

import static ai.strgm.util.Nulls.mapNullable;
import static java.lang.Integer.parseInt;
import static java.lang.Thread.currentThread;
import static java.util.Comparator.comparing;
import static java.util.Comparator.comparingInt;
import static java.util.Comparator.reverseOrder;

import ai.strgm.dealer.client.DeckRegistry;
import ai.strgm.dealer.config.DealerProperties;
import ai.strgm.dealer.config.DealerProperties.ClientConfig;
import ai.strgm.dealer.exception.LeaseBlockedException;
import ai.strgm.dealer.exception.LeaseUnavailableException;
import ai.strgm.dealer.model.AutoTier;
import ai.strgm.dealer.model.DeckServerRef;
import ai.strgm.dealer.model.Eviction;
import ai.strgm.dealer.model.LeaseRequest;
import ai.strgm.dealer.model.LeaseRequestSnapshot;
import ai.strgm.dealer.model.ModelProfileRef;
import ai.strgm.dealer.model.Placement;
import ai.strgm.dealer.model.Session;
import ai.strgm.dealer.model.SessionSnapshot;
import ai.strgm.deck.api.model.LeaseState;
import ai.strgm.types.LeaseId;
import ai.strgm.types.ModelName;
import ai.strgm.types.Priority;
import ai.strgm.types.SessionId;
import ai.strgm.types.SessionRef;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    /** A virtual model's name, {@code auto-<N>k}: a request for a profile with a context window of at least N thousand tokens. */
    private static final Pattern VIRTUAL_MODEL_NAME = Pattern.compile("auto-(\\d+)k");
    /** An {@code auto-<N>k} model that isn't one of the {@link AutoTier}s needs N thousand tokens. */
    private static final int TOKENS_PER_K = 1000;

    private final DeckRegistry decks;
    private final DealerProperties properties;
    private final Object lock = new Object();
    // TODO(#109): keep the sessions, lease requests, reservations and evictions in a persistent store, so a DEALER
    // restart neither loses them nor orphans the sessions' DECK leases
    private final Map<SessionRef, Session> sessions = new LinkedHashMap<>();
    private final List<LeaseRequest> leaseRequests = new ArrayList<>();
    private final Map<String, LeaseRequest> reservations = new HashMap<>();
    private final Deque<Eviction> evictions = new ArrayDeque<>();
    private long nextArrivalOrder;

    public Scheduler(final DeckRegistry decks, final DealerProperties properties) {
        this.decks = decks;
        this.properties = properties;
    }

    /** The profiles a request may use, in preference order. */
    // TODO(#62): limit them to the DECK servers and model profiles in the client's stored configuration, instead of
    // every profile, with external servers only behind the client's external-routing flag
    List<ModelProfileRef> candidateProfiles(final ClientConfig clientConfig, final ModelName modelName) {
        final var allProfileRefs = decks.profiles();
        final var virtualModelContextWindow = virtualModelContextWindow(modelName);
        if (virtualModelContextWindow.isPresent()) {
            final int minContextWindow = virtualModelContextWindow.getAsInt();
            final var localProfileRefs = allProfileRefs.stream()
                    .filter(profileRef -> !profileRef.external() && profileRef.profile().contextWindow() >= minContextWindow)
                    .sorted(comparingInt(profileRef -> profileRef.profile().contextWindow()))
                    .toList();
            final var externalProfileRefs = clientConfig.externalRouting()
                    ? allProfileRefs.stream()
                            .filter(profileRef -> profileRef.external() && profileRef.profile().contextWindow() >= minContextWindow)
                            .toList()
                    : List.<ModelProfileRef>of();
            return Stream.concat(localProfileRefs.stream(), externalProfileRefs.stream())
                    .toList();
        }
        // a client may name a profile by its id as well as by its model
        return allProfileRefs.stream()
                .filter(profileRef -> profileRef.profile().id().value().equals(modelName.value()) || profileRef.profile().modelName().equals(modelName))
                .filter(profileRef -> !profileRef.external() || clientConfig.externalRouting())
                .toList();
    }

    /**
     * The minimum context window a virtual model asks for: an {@link AutoTier}'s, or N thousand tokens for any other
     * {@code auto-<N>k}. Empty when {@code modelName} isn't a virtual model.
     */
    private static OptionalInt virtualModelContextWindow(final ModelName modelName) {
        final var virtualModelMatch = VIRTUAL_MODEL_NAME.matcher(modelName.value());
        if (!virtualModelMatch.matches()) {
            return OptionalInt.empty();
        }
        final int minContextWindow = AutoTier.ALL.stream()
                .filter(tier -> tier.modelName().equals(modelName))
                .mapToInt(AutoTier::contextWindow)
                .findFirst()
                .orElse(parseInt(virtualModelMatch.group(1)) * TOKENS_PER_K);
        return OptionalInt.of(minContextWindow);
    }

    /** Blocks until the request is placed on a ready lease. */
    public Placement acquireLease(final ClientConfig clientConfig, final SessionId sessionId, final ModelName modelName, final Priority requestPriority) {
        final var placement = placeRequest(clientConfig, sessionId, modelName, requestPriority);
        try {
            awaitReady(placement);
        } catch (RuntimeException exception) {
            // the request won't be served: undo its in-flight count, so the lease can still idle out or be evicted,
            // and keep the lease, so a retry can use it (#101)
            endRequest(placement, 0, 0, 0);
            throw exception;
        }
        return placement;
    }

    /**
     * Places the request on its session's lease, or queues it for a new one. A session queues one acquisition at a
     * time (#64): a request that finds one queued waits for it, then tries again.
     */
    private Placement placeRequest(final ClientConfig clientConfig, final SessionId sessionId, final ModelName modelName, final Priority requestPriority) {
        final Session session;
        synchronized (lock) {
            session = sessions.computeIfAbsent(sessionKey(clientConfig, sessionId), sessionRef -> new Session(clientConfig, sessionId));
            session.recordRequest();
        }
        while (true) {
            final LeaseRequest leaseRequest;
            // The choice below is made under the lock, so two requests can't both queue a lease request. The waiting
            // happens outside it, because the dispatch() that places a lease request later, from endRequest() or tick(),
            // needs the lock too. queuedAhead says whose lease request it is. The owner dispatches it and gets its
            // placement, already counted in flight, so the new lease can't be released or evicted before it's used;
            // only the owner cancels it. A request queued behind another's waits for it to settle, then tries again.
            final boolean queuedAhead;
            synchronized (lock) {
                if (session.pendingLeaseRequest() != null) {
                    leaseRequest = session.pendingLeaseRequest();
                    queuedAhead = true;
                } else if (session.canReuseLease(modelName)) {
                    return session.reuseLease();
                } else {
                    leaseRequest = new LeaseRequest(session, modelName, requestPriority, nextArrivalOrder++);
                    leaseRequests.add(leaseRequest);
                    session.queue(leaseRequest);
                    queuedAhead = false;
                }
            }
            if (!queuedAhead) {
                dispatch();
                return awaitPlacement(leaseRequest);
            }
            awaitSettled(leaseRequest);
        }
    }

    /**
     * A session's key: the reference DECK knows it by. Session ids are scoped to their client, and two clients can
     * both have a session {@code s-1}, so the key holds both.
     */
    private static SessionRef sessionKey(final ClientConfig clientConfig, final SessionId sessionId) {
        return SessionRef.of(clientConfig.name(), sessionId);
    }

    /** Waits for the request's own acquisition to be placed. */
    private Placement awaitPlacement(final LeaseRequest leaseRequest) {
        try {
            return leaseRequest.placement().get();
        } catch (InterruptedException exception) {
            currentThread().interrupt();
            cancel(leaseRequest);
            throw new LeaseUnavailableException("Interrupted while queued");
        } catch (ExecutionException exception) {
            final var reason = mapNullable(exception.getCause(), Throwable::getMessage);
            throw new LeaseUnavailableException(reason != null ? reason : "The request couldn't be placed");
        }
    }

    /** Waits, without holding the lock, for another request's acquisition in the session to be placed or cancelled. */
    private static void awaitSettled(final LeaseRequest leaseRequest) {
        try {
            leaseRequest.placement().get();
        } catch (ExecutionException exception) {
            // it was cancelled: the next attempt decides what this request does
        } catch (InterruptedException exception) {
            currentThread().interrupt();
            throw new LeaseUnavailableException("Interrupted while queued");
        }
    }

    private void cancel(final LeaseRequest leaseRequest) {
        synchronized (lock) {
            leaseRequests.remove(leaseRequest);
            reservations.values().removeIf(reservingLeaseRequest -> reservingLeaseRequest == leaseRequest);
            leaseRequest.session().dequeue(leaseRequest);
            // wakes the requests waiting for it; a placement made just before the cancel is undone
            if (!leaseRequest.placement().completeExceptionally(new IllegalStateException("The queued request was cancelled"))) {
                endRequest(leaseRequest.placement().join(), 0, 0, 0);
            }
        }
    }

    /** Waits for the placement's lease to be ready, asking DECK until the session knows it is. */
    private void awaitReady(final Placement placement) {
        final var deadline = Instant.now().plus(Duration.ofSeconds(properties.timers().readinessTimeoutSeconds()));
        while (Instant.now().isBefore(deadline)) {
            synchronized (lock) {
                if (placement.session().isReady()) {
                    return;
                }
            }
            final var lease = decks.lease(placement.profileRef().serverRef(), placement.leaseId());
            if (lease.state() == LeaseState.READY) {
                synchronized (lock) {
                    placement.session().markReady(lease);
                }
                return;
            }
            sleep(properties.timers().readinessPollMillis());
        }
        throw new LeaseUnavailableException("Lease " + placement.leaseId() + " never became ready");
    }

    /** Called when a request's stream ends: accounts usage and applies a pending eviction. */
    public void endRequest(final Placement placement, final long promptTokens, final long cachedTokens, final long completionTokens) {
        synchronized (lock) {
            placement.session().endRequest(promptTokens, cachedTokens, completionTokens, placement.profileRef().profile().prices());
            if (placement.session().evictionDue()) {
                evictLeaseNow(placement.session());
            }
        }
        dispatch();
    }

    private void evictLeaseNow(final Session session) {
        final var evictedLease = session.clearLeaseForEviction();
        if (evictedLease != null) {
            decks.evictLease(evictedLease.profileRef().serverRef(), evictedLease.id());
            log.info("Evicted {} from {}", session.ref(), evictedLease.profileRef().key());
        }
    }

    private boolean reservedForOther(final ModelProfileRef candidate, final LeaseRequest leaseRequest) {
        return reservations.entrySet().stream()
                .filter(reservation -> reservation.getValue() != leaseRequest)
                .flatMap(reservation -> profileRefFor(reservation.getKey()).stream())
                .anyMatch(reservedProfileRef -> reservedProfileRef.key().equals(candidate.key())
                        || (reservedProfileRef.serverRef() == candidate.serverRef() && (reservedProfileRef.profile().reduces(candidate.profile().id())
                                || candidate.profile().reduces(reservedProfileRef.profile().id()))));
    }

    /** The model profile whose key is {@code profileKey}, if a DECK server still offers it. */
    private Optional<ModelProfileRef> profileRefFor(final String profileKey) {
        return decks.profiles().stream()
                .filter(profileRef -> profileRef.key().equals(profileKey))
                .findFirst();
    }

    /** Places every placeable lease request, in priority order, and starts evictions where client priority allows. */
    public void dispatch() {
        synchronized (lock) {
            // A lease request whose session still holds a lease is a model switch, or a request that came while the
            // lease was being evicted. Once the session's requests in flight end, the old lease goes first: released
            // here for a model switch, evicted by endRequest() for an eviction. So a session never holds two leases (#64).
            leaseRequests.forEach(leaseRequest -> {
                final var releasedLease = leaseRequest.session().clearLeaseForSwitch();
                if (releasedLease != null) {
                    decks.releaseLease(releasedLease.profileRef().serverRef(), releasedLease.id());
                }
            });
            leaseRequests.sort(comparing((LeaseRequest queued) -> queued.session().clientConfig().priority(), reverseOrder())
                    .thenComparing(LeaseRequest::requestPriority, reverseOrder()).thenComparingLong(LeaseRequest::arrivalOrder));
            final var queue = leaseRequests.iterator();
            while (queue.hasNext()) {
                final var leaseRequest = queue.next();
                final var session = leaseRequest.session();
                if (session.holdsLease()) {
                    continue;
                }
                ModelProfileRef firstBlocked = null;
                List<LeaseId> firstBlockers = null;
                boolean placed = false;
                for (final var candidate : candidateProfiles(session.clientConfig(), leaseRequest.modelName())) {
                    if (reservedForOther(candidate, leaseRequest)) {
                        continue;
                    }
                    try {
                        final var lease = decks.acquireLease(candidate, session.ref());
                        final var placement = session.placeOnLease(leaseRequest, candidate, lease);
                        queue.remove();
                        reservations.values().removeIf(reservingLeaseRequest -> reservingLeaseRequest == leaseRequest);
                        leaseRequest.placement().complete(placement);
                        log.info("Placed {} on {} ({})", session.ref(), candidate.key(), lease.state().getValue());
                        placed = true;
                        break;
                    } catch (LeaseBlockedException exception) {
                        if (firstBlocked == null) {
                            firstBlocked = candidate;
                            firstBlockers = exception.blockers();
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

    private void considerEviction(final LeaseRequest leaseRequest, final ModelProfileRef target, final List<LeaseId> blockers) {
        final var victims = blockers.stream()
                .flatMap(leaseId -> leaseHolderOf(leaseId, target.serverRef()).stream())
                .filter(leaseHolder -> leaseHolder.clientConfig().priority().compareTo(leaseRequest.session().clientConfig().priority()) < 0)
                .toList();
        // evicts only when every blocking lease has a holder of lower client priority than the request's
        if (victims.size() < blockers.size()) {
            return;
        }
        reservations.putIfAbsent(target.key(), leaseRequest);
        final var now = Instant.now();
        final var minLease = Duration.ofSeconds(properties.timers().minLeaseSeconds());
        victims.forEach(victim -> {
            if (victim.markEvicting(now, minLease)) {
                evictions.addLast(new Eviction(now, victim.clientConfig().name(), victim.id(), target.serverRef().name(),
                        mapNullable(victim.heldLease(), victimLease -> victimLease.profileRef().profile().id()),
                        leaseRequest.session().clientConfig().name(),
                        "priority " + leaseRequest.session().clientConfig().priority() + " > " + victim.clientConfig().priority() + " needs " + target.profile().id()));
                while (evictions.size() > properties.evictionHistory()) {
                    evictions.removeFirst();
                }
                log.info("Evicting {} for {} (needs {})", victim.ref(), leaseRequest.session().ref(), target.key());
                if (victim.inFlight() == 0) {
                    evictLeaseNow(victim);
                }
            }
        });
    }

    /** The session holding {@code leaseId} on {@code serverRef}, if any. */
    private Optional<Session> leaseHolderOf(final LeaseId leaseId, final DeckServerRef serverRef) {
        return sessions.values().stream()
                .filter(session -> session.holds(leaseId, serverRef))
                .findFirst();
    }

    /** Releases leases idle for longer than N, then re-dispatches (evictions wait for M to pass). */
    @Scheduled(fixedDelayString = "${dealer.timers.tick-millis}")
    public void tick() {
        synchronized (lock) {
            final var idle = Duration.ofSeconds(properties.timers().idleSeconds());
            final var now = Instant.now();
            sessions.values().forEach(session -> {
                final var releasedLease = session.clearLeaseIfIdle(now, idle);
                if (releasedLease != null) {
                    decks.releaseLease(releasedLease.profileRef().serverRef(), releasedLease.id());
                    log.info("Released idle lease of {} on {}", session.ref(), releasedLease.profileRef().key());
                }
            });
        }
        dispatch();
    }

    public List<LeaseRequestSnapshot> queuedLeaseRequests() {
        synchronized (lock) {
            final var now = Instant.now();
            return leaseRequests.stream()
                    .map(leaseRequest -> new LeaseRequestSnapshot(leaseRequest.session().clientConfig().name(), leaseRequest.session().id(),
                            leaseRequest.modelName(), leaseRequest.session().clientConfig().priority(), leaseRequest.requestPriority(),
                            Duration.between(leaseRequest.enqueuedAt(), now), reservedProfile(leaseRequest)))
                    .toList();
        }
    }

    /** The profile being drained for a lease request, if any. */
    private @Nullable String reservedProfile(final LeaseRequest leaseRequest) {
        return reservations.entrySet().stream()
                .filter(reservation -> reservation.getValue() == leaseRequest)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    public List<SessionSnapshot> sessions() {
        synchronized (lock) {
            final var now = Instant.now();
            return sessions.values().stream()
                    .map(session -> session.snapshot(now))
                    .toList();
        }
    }

    public List<Eviction> evictions() {
        synchronized (lock) {
            return List.copyOf(evictions);
        }
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            currentThread().interrupt();
        }
    }
}
