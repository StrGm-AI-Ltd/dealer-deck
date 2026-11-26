package ai.strgm.deck;

import ai.strgm.deck.api.model.Lease;
import ai.strgm.deck.api.model.LeaseState;
import ai.strgm.types.LeaseId;
import ai.strgm.types.ProfileId;
import ai.strgm.types.SessionRef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

/**
 * What both DECK implementations share: the lease store, and the lease operations that don't depend on how a
 * server manages its capacity. One lock, {@link #lock}, guards the store, every lease's state and usage, and a
 * server's own capacity state, so a capacity check and the grant it allows happen together.
 */
public abstract class AbstractDeckServer implements DeckServer {
    /** How many of the most recent leases {@link #state()} reports. */
    private static final int RECENT_LEASES = 30;

    protected final DeckProperties properties;
    protected final ObjectMapper mapper;
    /**
     * Guards the lease store and a subclass's own capacity state (e.g. which profile each simulated worker has
     * loaded). A private object rather than this server's monitor, which any holder of the bean could lock.
     */
    protected final Object lock = new Object();
    /** Every lease this server has granted, oldest first. */
    private final Map<LeaseId, LeaseRecord> leases = new LinkedHashMap<>();

    protected AbstractDeckServer(final DeckProperties properties, final ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    protected static ApiException unknownProfile(final ProfileId profileId) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", "Unknown profile: " + profileId);
    }

    private LeaseRecord record(final LeaseId leaseId) {
        final var lease = leases.get(leaseId);
        if (lease == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "Unknown lease: " + leaseId);
        }
        return lease;
    }

    /** The lease a chat request names, which must still be active. */
    protected LeaseRecord activeRecord(final LeaseId leaseId) {
        synchronized (lock) {
            final var lease = record(leaseId);
            if (!lease.active()) {
                throw new ApiException(HttpStatus.CONFLICT, "lease_inactive", "Lease " + leaseId + " is " + lease.state().getValue());
            }
            return lease;
        }
    }

    /** The active leases, oldest first. Their ids and profiles are safe to read without the lock: they never change. */
    protected List<LeaseRecord> activeRecords() {
        synchronized (lock) {
            return leases.values().stream().filter(LeaseRecord::active).toList();
        }
    }

    /** The ids of the active leases on {@code profileId}. */
    protected List<LeaseId> active(final ProfileId profileId) {
        synchronized (lock) {
            return leases.values().stream().filter(lease -> lease.active() && lease.profileId().equals(profileId)).map(LeaseRecord::id).toList();
        }
    }

    /** Stores a new lease. For {@link #grant}, which runs under the lock. */
    protected LeaseRecord register(final ProfileId profileId, final SessionRef sessionRef, final LeaseState state) {
        synchronized (lock) {
            final var lease = new LeaseRecord(profileId, sessionRef, state);
            leases.put(lease.id(), lease);
            return lease;
        }
    }

    /** A loading lease becomes ready, e.g. once a simulated load finishes. */
    protected void markReady(final LeaseRecord lease) {
        synchronized (lock) {
            lease.markReady();
        }
    }

    protected boolean isLoading(final LeaseRecord lease) {
        synchronized (lock) {
            return lease.state() == LeaseState.LOADING;
        }
    }

    /** Adds a completion's tokens to its lease; {@code estimated} when the provider sent no usage. */
    protected void addUsage(final LeaseRecord lease, final long prompt, final long cached, final long completion, final boolean estimated) {
        synchronized (lock) {
            lease.addUsage(prompt, cached, completion, estimated);
        }
    }

    /** Every lease, oldest first, as the API reports it. */
    protected List<Lease> all() {
        synchronized (lock) {
            return leases.values().stream().map(LeaseRecord::toApi).toList();
        }
    }

    /** The {@value #RECENT_LEASES} most recent leases, oldest first, for {@link #state()}. */
    protected List<Lease> recent() {
        final var all = all();
        return all.subList(Math.max(0, all.size() - RECENT_LEASES), all.size());
    }

    /**
     * Idempotent per session and profile: a session that already holds an active lease on the profile gets that
     * lease back, so retrying an acquisition whose response was lost doesn't grant a second lease, which nothing
     * would ever release (#65).
     */
    @Override
    public final Acquisition acquire(final ProfileId profileId, final SessionRef sessionRef) {
        synchronized (lock) {
            final var held = leases.values().stream()
                    .filter(lease -> lease.active() && lease.profileId().equals(profileId) && lease.sessionRef().equals(sessionRef))
                    .findFirst();
            return held.isPresent() ? new Acquisition(held.get().toApi(), false) : new Acquisition(grant(profileId, sessionRef).toApi(), true);
        }
    }

    /**
     * Grants a new lease with {@link #register}, or throws if the profile is unknown or has no capacity. Runs under
     * the lock, so the capacity it checks can't change before the lease is stored.
     */
    protected abstract LeaseRecord grant(ProfileId profileId, SessionRef sessionRef);

    @Override
    public Lease get(final LeaseId leaseId) {
        synchronized (lock) {
            return record(leaseId).toApi();
        }
    }

    @Override
    public List<Lease> leases(final @Nullable ProfileId profileId, final @Nullable SessionRef sessionRef) {
        synchronized (lock) {
            return leases.values().stream().filter(LeaseRecord::active)
                    .filter(lease -> profileId == null || lease.profileId().equals(profileId))
                    .filter(lease -> sessionRef == null || sessionRef.equals(lease.sessionRef()))
                    .map(LeaseRecord::toApi).toList();
        }
    }

    @Override
    public void release(final LeaseId leaseId) {
        synchronized (lock) {
            record(leaseId).end(LeaseState.RELEASED);
        }
    }

    @Override
    public Lease evict(final LeaseId leaseId) {
        synchronized (lock) {
            final var lease = record(leaseId);
            lease.end(LeaseState.EVICTED);
            return lease.toApi();
        }
    }
}
