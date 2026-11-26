package ai.strgm.dealer;

import ai.strgm.dealer.DealerProperties.ClientDef;
import ai.strgm.dealer.DeckRegistry.ProfileRef;
import ai.strgm.dealer.api.model.EvictionEvent;
import ai.strgm.dealer.api.model.QueuedRequest;
import ai.strgm.dealer.api.model.ServerType;
import ai.strgm.dealer.api.model.SessionInfo;
import ai.strgm.deck.api.model.LeaseState;
import ai.strgm.deck.api.model.ModelProfile;
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
    private static final Map<String, Integer> TIERS = Map.of("32", 32768, "65", 65536, "128", 131072);

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
            final int min = TIERS.getOrDefault(auto.group(1), Integer.parseInt(auto.group(1)) * 1000);
            final var local = all.stream().filter(p -> !p.external() && p.profile().getContextWindow() >= min)
                    .sorted(Comparator.comparingInt(p -> p.profile().getContextWindow())).toList();
            final var external = client.externalRouting()
                    ? all.stream().filter(p -> p.external() && p.profile().getContextWindow() >= min).toList()
                    : List.<ProfileRef>of();
            return Stream.concat(local.stream(), external.stream()).toList();
        }
        return all.stream().filter(p -> p.profile().getId().equals(model) || p.profile().getModel().equals(model))
                .filter(p -> !p.external() || client.externalRouting()).toList();
    }

    public boolean isVirtual(final String model) {
        return AUTO.matcher(model).matches();
    }

    /** Blocks until the request is placed on a ready lease. */
    public Placement acquire(final ClientDef client, final String sessionId, final String model, final int requestPriority) {
        final Waiter waiter;
        synchronized (lock) {
            final var s = sessions.computeIfAbsent(client.name() + "|" + sessionId, k -> new Session(client, sessionId));
            s.requests++;
            s.lastActivity = System.currentTimeMillis();
            final var held = s.profile;
            final var heldLease = s.leaseId;
            if (held != null && heldLease != null && !s.evictPending && model.equals(s.model)) {
                s.inFlight++;
                return new Placement(s, held, heldLease);
            }
            if (held != null && heldLease != null && s.inFlight == 0 && !model.equals(s.model)) {
                decks.release(held.server(), heldLease);
                clearLease(s, "released");
            }
            s.model = model;
            waiter = new Waiter(s, model, requestPriority, seq++);
            waiters.add(waiter);
        }
        dispatch();
        final Placement placement;
        try {
            placement = waiter.future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancel(waiter);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "server_error", "Interrupted while queued");
        } catch (ExecutionException e) {
            final var cause = e.getCause();
            final var reason = cause == null ? null : cause.getMessage();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "server_error", reason != null ? reason : "The request couldn't be placed");
        }
        awaitReady(placement);
        return placement;
    }

    private void cancel(final Waiter w) {
        synchronized (lock) {
            waiters.remove(w);
            reservations.values().removeIf(r -> r == w);
        }
    }

    private void awaitReady(final Placement p) {
        final var deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            final var lease = decks.lease(p.profile().server(), p.leaseId());
            if (lease.getState() == LeaseState.READY) {
                synchronized (lock) {
                    p.session().leaseState = "ready";
                    p.session().leaseReadyAt = lease.getReadyAtMs() == null ? System.currentTimeMillis() : lease.getReadyAtMs();
                }
                return;
            }
            sleep(150);
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "server_error", "Lease " + p.leaseId() + " never became ready");
    }

    /** Called when a request's stream ends: accounts usage and applies a pending eviction. */
    public void release(final Placement p, final long prompt, final long cached, final long completion) {
        synchronized (lock) {
            final var s = p.session();
            s.inFlight = Math.max(0, s.inFlight - 1);
            s.lastActivity = System.currentTimeMillis();
            s.promptTokens += prompt;
            s.cachedTokens += cached;
            s.completionTokens += completion;
            final var prices = p.profile().profile().getPrices();
            if (prices != null) {
                s.costUsd += ((prompt - cached) * nz(prices.getInputPerMTok()) + cached * nz(prices.getCachedInputPerMTok())
                        + completion * nz(prices.getOutputPerMTok())) / 1_000_000.0;
            }
            if (s.evictPending && s.inFlight == 0) {
                evictNow(s);
            }
        }
        dispatch();
    }

    private static double nz(final @Nullable Double d) {
        return d == null ? 0 : d;
    }

    private void clearLease(final Session s, final String state) {
        s.leaseId = null;
        s.profile = null;
        s.evictPending = false;
        s.leaseState = state;
    }

    private void evictNow(final Session s) {
        final var held = s.profile;
        final var heldLease = s.leaseId;
        if (held != null && heldLease != null) {
            decks.evict(held.server(), heldLease);
            log.info("Evicted {} from {}", s.ref(), held.key());
        }
        clearLease(s, "evicted");
    }

    private boolean reservedForOther(final ProfileRef c, final Waiter w) {
        for (final var e : reservations.entrySet()) {
            if (e.getValue() == w) {
                continue;
            }
            final var reserved = decks.profiles().stream().filter(p -> p.key().equals(e.getKey())).findFirst();
            if (reserved.isEmpty()) {
                continue;
            }
            final var r = reserved.get();
            if (r.key().equals(c.key())) {
                return true;
            }
            if (r.server() == c.server() && (reduces(r.profile(), c.profile().getId()) || reduces(c.profile(), r.profile().getId()))) {
                return true;
            }
        }
        return false;
    }

    /** Whether a lease on {@code p} reduces the leases another profile on its server can grant. Any reduction counts. */
    private static boolean reduces(final ModelProfile p, final String otherId) {
        return p.getLimitations() != null && p.getLimitations().getLeaseReductions() != null
                && p.getLimitations().getLeaseReductions().stream().anyMatch(r -> r.getProfileId().equals(otherId));
    }

    /** Places every placeable waiter, in priority order, and starts evictions where client priority allows. */
    public void dispatch() {
        synchronized (lock) {
            waiters.sort(Comparator.<Waiter>comparingInt(w -> -w.session.client.priority())
                    .thenComparingInt(w -> -w.requestPriority).thenComparingLong(w -> w.seq));
            final var it = waiters.iterator();
            while (it.hasNext()) {
                final var w = it.next();
                ProfileRef firstBlocked = null;
                List<String> firstBlockers = null;
                boolean placed = false;
                for (final var c : candidates(w.session.client, w.model)) {
                    if (reservedForOther(c, w)) {
                        continue;
                    }
                    try {
                        final var lease = decks.acquire(c, w.session.ref());
                        final var s = w.session;
                        s.profile = c;
                        s.leaseId = lease.getId();
                        s.leaseState = lease.getState().getValue();
                        s.inFlight++;
                        it.remove();
                        reservations.values().removeIf(r -> r == w);
                        w.future.complete(new Placement(s, c, lease.getId()));
                        log.info("Placed {} on {} ({})", s.ref(), c.key(), lease.getState().getValue());
                        placed = true;
                        break;
                    } catch (DeckRegistry.Blocked b) {
                        if (firstBlocked == null) {
                            firstBlocked = c;
                            firstBlockers = b.blockers;
                        }
                    } catch (Exception e) {
                        log.warn("Lease on {} failed: {}", c.key(), e.getMessage());
                    }
                }
                if (!placed && firstBlocked != null && firstBlockers != null) {
                    considerEviction(w, firstBlocked, firstBlockers);
                }
            }
        }
    }

    private void considerEviction(final Waiter w, final ProfileRef target, final List<String> blockers) {
        final List<Session> victims = new ArrayList<>();
        for (final var leaseId : blockers) {
            final var holder = sessions.values().stream()
                    .filter(s -> leaseId.equals(s.leaseId) && s.profile != null && s.profile.server() == target.server())
                    .findFirst();
            if (holder.isEmpty() || holder.get().client.priority() >= w.session.client.priority()) {
                return;
            }
            victims.add(holder.get());
        }
        reservations.putIfAbsent(target.key(), w);
        final var now = System.currentTimeMillis();
        final var minLeaseMs = props.timers().minLeaseSeconds() * 1000L;
        for (final var v : victims) {
            if (v.evictPending || v.leaseReadyAt + minLeaseMs > now) {
                continue;
            }
            v.evictPending = true;
            v.leaseState = "evicting";
            evictions.addLast(new EvictionEvent().createdAt(now / 1000).victimClient(v.client.name()).victimSession(v.id)
                    .deckServer(target.server().name()).profileId(v.profile == null ? null : v.profile.profile().getId()).byClient(w.session.client.name())
                    .reason("priority " + w.session.client.priority() + " > " + v.client.priority() + " needs " + target.profile().getId()));
            while (evictions.size() > 50) {
                evictions.removeFirst();
            }
            log.info("Evicting {} for {} (needs {})", v.ref(), w.session.ref(), target.key());
            if (v.inFlight == 0) {
                evictNow(v);
            }
        }
    }

    /** Releases leases idle for longer than N, then re-dispatches (evictions wait for M to pass). */
    @Scheduled(fixedDelay = 500)
    public void tick() {
        synchronized (lock) {
            final var idleMs = props.timers().idleSeconds() * 1000L;
            final var now = System.currentTimeMillis();
            for (final var s : sessions.values()) {
                final var held = s.profile;
                final var heldLease = s.leaseId;
                if (held != null && heldLease != null && s.inFlight == 0 && !s.evictPending && now - s.lastActivity > idleMs) {
                    decks.release(held.server(), heldLease);
                    log.info("Released idle lease of {} on {}", s.ref(), held.key());
                    clearLease(s, "released (idle)");
                }
            }
        }
        dispatch();
    }

    public List<QueuedRequest> queue() {
        synchronized (lock) {
            final var now = System.currentTimeMillis();
            return waiters.stream().map(w -> new QueuedRequest().client(w.session.client.name()).sessionId(w.session.id)
                    .model(w.model).clientPriority(w.session.client.priority()).requestPriority(w.requestPriority)
                    .waitingMs(now - w.enqueuedAt).reservedProfile(reservations.entrySet().stream()
                            .filter(e -> e.getValue() == w).map(Map.Entry::getKey).findFirst().orElse(null)))
                    .toList();
        }
    }

    public List<SessionInfo> sessions() {
        synchronized (lock) {
            final var now = System.currentTimeMillis();
            return sessions.values().stream().map(s -> new SessionInfo().client(s.client.name()).sessionId(s.id).model(s.model)
                    .deckServer(s.profile == null ? null : s.profile.server().name())
                    .serverType(s.profile == null ? null : ServerType.fromValue(s.profile.server().def().egress()))
                    .profileId(s.profile == null ? null : s.profile.profile().getId()).leaseId(s.leaseId)
                    .leaseState(s.leaseState).leaseAgeMs(s.leaseId == null || s.leaseReadyAt == 0 ? null : now - s.leaseReadyAt)
                    .idleMs(s.inFlight > 0 ? 0L : now - s.lastActivity).inFlight(s.inFlight).evictPending(s.evictPending)
                    .requests(s.requests).promptTokens(s.promptTokens).cachedTokens(s.cachedTokens)
                    .completionTokens(s.completionTokens).costUsd(s.costUsd))
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
