package ai.strgm.dealer;

import ai.strgm.dealer.DealerProperties.ClientDef;
import ai.strgm.dealer.DeckRegistry.ProfileRef;
import ai.strgm.dealer.api.model.EvictionEvent;
import ai.strgm.dealer.api.model.QueuedRequest;
import ai.strgm.dealer.api.model.ServerType;
import ai.strgm.dealer.api.model.SessionInfo;
import ai.strgm.deck.api.model.Lease;
import ai.strgm.deck.api.model.LeaseState;
import ai.strgm.deck.api.model.ModelProfile;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The router queue, placement and eviction.
 * <ul>
 * <li>A session keeps its lease across requests (sticky) until the lease idles out or is evicted.</li>
 * <li>Dispatch places every placeable request in priority order: a request blocked on one server doesn't
 * hold up one that another server can take (D97).</li>
 * <li>Only client priority evicts (D59), between requests (D31), and not before the minimum lease time M.
 * Capacity being drained for a waiter is reserved for it, so a victim can't re-take it (D97).</li>
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
    /** Prices are per million tokens. */
    private static final double TOKENS_PER_MILLION = 1_000_000.0;
    /** How long a request waits for its lease to become ready. */
    private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(90);
    private static final long READINESS_POLL_MS = 150;
    /** How many eviction events {@code /metrics/state} keeps. */
    private static final int MAX_EVICTION_EVENTS = 50;
    private static final long TICK_INTERVAL_MS = 500;

    static final class Session {
        final ClientDef client;
        final String id;
        @Nullable String model;
        @Nullable ProfileRef profile;
        @Nullable String leaseId;
        String leaseState = "none";
        long leaseReadyAt;
        long lastActivity = System.currentTimeMillis();
        int inFlight;
        boolean evictPending;
        int requests;
        long promptTokens;
        long cachedTokens;
        long completionTokens;
        double costUsd;

        Session(final ClientDef client, final String id) {
            this.client = client;
            this.id = id;
        }

        String ref() {
            return client.name() + "/" + id;
        }
    }

    static final class Waiter {
        final Session session;
        final String model;
        final int requestPriority;
        final long enqueuedAt = System.currentTimeMillis();
        final long seq;
        final CompletableFuture<Placement> future = new CompletableFuture<>();

        Waiter(final Session session, final String model, final int requestPriority, final long seq) {
            this.session = session;
            this.model = model;
            this.requestPriority = requestPriority;
            this.seq = seq;
        }
    }

    public record Placement(Session session, ProfileRef profile, String leaseId) {}

    private final DeckRegistry decks;
    private final DealerProperties props;
    private final Object lock = new Object();
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final List<Waiter> waiters = new ArrayList<>();
    private final Map<String, Waiter> reservations = new HashMap<>();
    private final Deque<EvictionEvent> evictions = new ArrayDeque<>();
    private long seq;

    public Scheduler(final DeckRegistry decks, final DealerProperties props) {
        this.decks = decks;
        this.props = props;
    }

    /** The profiles a request may use, in preference order. */
    List<ProfileRef> candidates(final ClientDef client, final String model) {
        final var all = decks.profiles();
        final var auto = AUTO.matcher(model);
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
        return all.stream().filter(profileRef -> profileRef.profile().getId().equals(model) || profileRef.profile().getModel().equals(model))
                .filter(profileRef -> !profileRef.external() || client.externalRouting()).toList();
    }

    public boolean isVirtual(final String model) {
        return AUTO.matcher(model).matches();
    }

    /** Blocks until the request is placed on a ready lease. */
    public Placement acquire(final ClientDef client, final String sessionId, final String model, final int requestPriority) {
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

    /** Places the request on its session's lease, or queues it for a new one. */
    private Placement place(final ClientDef client, final String sessionId, final String model, final int requestPriority) {
        final Waiter waiter;
        synchronized (lock) {
            final var session = sessions.computeIfAbsent(client.name() + "|" + sessionId, key -> new Session(client, sessionId));
            session.requests++;
            session.lastActivity = System.currentTimeMillis();
            final var held = session.profile;
            final var heldLease = session.leaseId;
            if (held != null && heldLease != null && !session.evictPending && model.equals(session.model)) {
                session.inFlight++;
                return new Placement(session, held, heldLease);
            }
            if (held != null && heldLease != null && session.inFlight == 0 && !model.equals(session.model)) {
                decks.release(held.server(), heldLease);
                clearLease(session, "released");
            }
            session.model = model;
            waiter = new Waiter(session, model, requestPriority, seq++);
            waiters.add(waiter);
        }
        dispatch();
        try {
            return waiter.future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancel(waiter);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "server_error", "Interrupted while queued");
        } catch (ExecutionException exception) {
            final var cause = exception.getCause();
            final var reason = cause == null ? null : cause.getMessage();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "server_error", reason != null ? reason : "The request couldn't be placed");
        }
    }

    private void cancel(final Waiter waiter) {
        synchronized (lock) {
            waiters.remove(waiter);
            reservations.values().removeIf(reserved -> reserved == waiter);
        }
    }

    /** Waits for the placement's lease to be ready, asking DECK until the session knows it is. */
    private void awaitReady(final Placement placement) {
        final var session = placement.session();
        final var deadline = System.currentTimeMillis() + READINESS_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            synchronized (lock) {
                if (LeaseState.READY.getValue().equals(session.leaseState)) {
                    return;
                }
            }
            final var lease = decks.lease(placement.profile().server(), placement.leaseId());
            if (lease.getState() == LeaseState.READY) {
                synchronized (lock) {
                    markReady(session, lease);
                }
                return;
            }
            sleep(READINESS_POLL_MS);
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "server_error", "Lease " + placement.leaseId() + " never became ready");
    }

    private static void markReady(final Session session, final Lease lease) {
        session.leaseState = LeaseState.READY.getValue();
        session.leaseReadyAt = lease.getReadyAtMs() == null ? System.currentTimeMillis() : lease.getReadyAtMs();
    }

    /** Called when a request's stream ends: accounts usage and applies a pending eviction. */
    public void release(final Placement placement, final long prompt, final long cached, final long completion) {
        synchronized (lock) {
            final var session = placement.session();
            session.inFlight = Math.max(0, session.inFlight - 1);
            session.lastActivity = System.currentTimeMillis();
            session.promptTokens += prompt;
            session.cachedTokens += cached;
            session.completionTokens += completion;
            final var prices = placement.profile().profile().getPrices();
            if (prices != null) {
                session.costUsd += ((prompt - cached) * nz(prices.getInputPerMTok()) + cached * nz(prices.getCachedInputPerMTok())
                        + completion * nz(prices.getOutputPerMTok())) / TOKENS_PER_MILLION;
            }
            if (session.evictPending && session.inFlight == 0) {
                evictNow(session);
            }
        }
        dispatch();
    }

    private static double nz(final @Nullable Double price) {
        return price == null ? 0 : price;
    }

    private void clearLease(final Session session, final String state) {
        session.leaseId = null;
        session.profile = null;
        session.evictPending = false;
        session.leaseState = state;
    }

    private void evictNow(final Session session) {
        final var held = session.profile;
        final var heldLease = session.leaseId;
        if (held != null && heldLease != null) {
            decks.evict(held.server(), heldLease);
            log.info("Evicted {} from {}", session.ref(), held.key());
        }
        clearLease(session, "evicted");
    }

    private boolean reservedForOther(final ProfileRef candidate, final Waiter waiter) {
        for (final var reservation : reservations.entrySet()) {
            if (reservation.getValue() == waiter) {
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
    private static boolean reduces(final ModelProfile profile, final String otherId) {
        return profile.getLimitations() != null && profile.getLimitations().getLeaseReductions() != null
                && profile.getLimitations().getLeaseReductions().stream().anyMatch(reduction -> reduction.getProfileId().equals(otherId));
    }

    /** Places every placeable waiter, in priority order, and starts evictions where client priority allows. */
    public void dispatch() {
        synchronized (lock) {
            waiters.sort(Comparator.<Waiter>comparingInt(queued -> -queued.session.client.priority())
                    .thenComparingInt(queued -> -queued.requestPriority).thenComparingLong(queued -> queued.seq));
            final var queue = waiters.iterator();
            while (queue.hasNext()) {
                final var waiter = queue.next();
                ProfileRef firstBlocked = null;
                List<String> firstBlockers = null;
                boolean placed = false;
                for (final var candidate : candidates(waiter.session.client, waiter.model)) {
                    if (reservedForOther(candidate, waiter)) {
                        continue;
                    }
                    try {
                        final var lease = decks.acquire(candidate, waiter.session.ref());
                        final var session = waiter.session;
                        session.profile = candidate;
                        session.leaseId = lease.getId();
                        if (lease.getState() == LeaseState.READY) {
                            markReady(session, lease);
                        } else {
                            session.leaseState = lease.getState().getValue();
                        }
                        session.inFlight++;
                        queue.remove();
                        reservations.values().removeIf(reserved -> reserved == waiter);
                        waiter.future.complete(new Placement(session, candidate, lease.getId()));
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
                    considerEviction(waiter, firstBlocked, firstBlockers);
                }
            }
        }
    }

    private void considerEviction(final Waiter waiter, final ProfileRef target, final List<String> blockers) {
        final List<Session> victims = new ArrayList<>();
        for (final var leaseId : blockers) {
            final var holder = sessions.values().stream()
                    .filter(session -> leaseId.equals(session.leaseId) && session.profile != null && session.profile.server() == target.server())
                    .findFirst();
            if (holder.isEmpty() || holder.get().client.priority() >= waiter.session.client.priority()) {
                return;
            }
            victims.add(holder.get());
        }
        reservations.putIfAbsent(target.key(), waiter);
        final var now = System.currentTimeMillis();
        final var minLeaseMs = Duration.ofSeconds(props.timers().minLeaseSeconds()).toMillis();
        for (final var victim : victims) {
            if (victim.evictPending || victim.leaseReadyAt + minLeaseMs > now) {
                continue;
            }
            victim.evictPending = true;
            victim.leaseState = "evicting";
            evictions.addLast(new EvictionEvent().createdAt(Instant.ofEpochMilli(now).getEpochSecond()).victimClient(victim.client.name()).victimSession(victim.id)
                    .deckServer(target.server().name()).profileId(victim.profile == null ? null : victim.profile.profile().getId()).byClient(waiter.session.client.name())
                    .reason("priority " + waiter.session.client.priority() + " > " + victim.client.priority() + " needs " + target.profile().getId()));
            while (evictions.size() > MAX_EVICTION_EVENTS) {
                evictions.removeFirst();
            }
            log.info("Evicting {} for {} (needs {})", victim.ref(), waiter.session.ref(), target.key());
            if (victim.inFlight == 0) {
                evictNow(victim);
            }
        }
    }

    /** Releases leases idle for longer than N, then re-dispatches (evictions wait for M to pass). */
    @Scheduled(fixedDelay = TICK_INTERVAL_MS)
    public void tick() {
        synchronized (lock) {
            final var idleMs = Duration.ofSeconds(props.timers().idleSeconds()).toMillis();
            final var now = System.currentTimeMillis();
            for (final var session : sessions.values()) {
                final var held = session.profile;
                final var heldLease = session.leaseId;
                if (held != null && heldLease != null && session.inFlight == 0 && !session.evictPending && now - session.lastActivity > idleMs) {
                    decks.release(held.server(), heldLease);
                    log.info("Released idle lease of {} on {}", session.ref(), held.key());
                    clearLease(session, "released (idle)");
                }
            }
        }
        dispatch();
    }

    public List<QueuedRequest> queue() {
        synchronized (lock) {
            final var now = System.currentTimeMillis();
            return waiters.stream().map(waiter -> new QueuedRequest().client(waiter.session.client.name()).sessionId(waiter.session.id)
                    .model(waiter.model).clientPriority(waiter.session.client.priority()).requestPriority(waiter.requestPriority)
                    .waitingMs(now - waiter.enqueuedAt).reservedProfile(reservations.entrySet().stream()
                            .filter(reservation -> reservation.getValue() == waiter).map(Map.Entry::getKey).findFirst().orElse(null)))
                    .toList();
        }
    }

    public List<SessionInfo> sessions() {
        synchronized (lock) {
            final var now = System.currentTimeMillis();
            return sessions.values().stream().map(session -> new SessionInfo().client(session.client.name()).sessionId(session.id).model(session.model)
                    .deckServer(session.profile == null ? null : session.profile.server().name())
                    .serverType(session.profile == null ? null : ServerType.fromValue(session.profile.server().def().egress()))
                    .profileId(session.profile == null ? null : session.profile.profile().getId()).leaseId(session.leaseId)
                    .leaseState(session.leaseState).leaseAgeMs(session.leaseId == null || session.leaseReadyAt == 0 ? null : now - session.leaseReadyAt)
                    .idleMs(session.inFlight > 0 ? 0L : now - session.lastActivity).inFlight(session.inFlight).evictPending(session.evictPending)
                    .requests(session.requests).promptTokens(session.promptTokens).cachedTokens(session.cachedTokens)
                    .completionTokens(session.completionTokens).costUsd(session.costUsd))
                    .toList();
        }
    }

    public List<EvictionEvent> evictions() {
        synchronized (lock) {
            return List.copyOf(evictions);
        }
    }

    private static void sleep(final long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
