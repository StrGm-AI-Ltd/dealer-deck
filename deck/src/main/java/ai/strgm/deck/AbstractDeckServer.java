package ai.strgm.deck;

import ai.strgm.deck.api.model.Lease;
import ai.strgm.deck.api.model.LeaseState;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

/**
 * What both DECK implementations share: the lease store, and the lease operations that don't depend on how a
 * server manages its capacity. A lease's state changes only under this object's lock.
 */
public abstract class AbstractDeckServer implements DeckServer {
    /** How many of the most recent leases {@link #state()} reports. */
    private static final int RECENT_LEASES = 30;

    protected final DeckProperties props;
    protected final ObjectMapper mapper;
    /** Every lease this server has granted, oldest first. */
    protected final Map<String, LeaseRecord> leases = Collections.synchronizedMap(new LinkedHashMap<>());

    protected AbstractDeckServer(final DeckProperties props, final ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    protected static ApiException unknownProfile(final String profileId) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", "Unknown profile: " + profileId);
    }

    protected LeaseRecord record(final String leaseId) {
        final var lease = leases.get(leaseId);
        if (lease == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "Unknown lease: " + leaseId);
        }
        return lease;
    }

    /** The lease a chat request names, which must still be active. */
    protected LeaseRecord activeRecord(final String leaseId) {
        final var lease = record(leaseId);
        if (!lease.active()) {
            throw new ApiException(HttpStatus.CONFLICT, "lease_inactive", "Lease " + leaseId + " is " + lease.state.getValue());
        }
        return lease;
    }

    /** The ids of the active leases on {@code profileId}. */
    protected List<String> active(final String profileId) {
        synchronized (leases) {
            return leases.values().stream().filter(lease -> lease.active() && lease.profileId.equals(profileId)).map(lease -> lease.id).toList();
        }
    }

    /** Every lease, oldest first. */
    protected List<LeaseRecord> all() {
        synchronized (leases) {
            return List.copyOf(leases.values());
        }
    }

    /** The {@value #RECENT_LEASES} most recent leases, oldest first, for {@link #state()}. */
    protected List<Lease> recent() {
        final var all = all();
        return all.subList(Math.max(0, all.size() - RECENT_LEASES), all.size()).stream().map(LeaseRecord::toApi).toList();
    }

    /**
     * Idempotent per session and profile: a session that already holds an active lease on the profile gets that
     * lease back, so retrying an acquisition whose response was lost doesn't grant a second lease, which nothing
     * would ever release (#65).
     */
    @Override
    public final synchronized Acquisition acquire(final String profileId, final String sessionRef) {
        final var held = activeLease(profileId, sessionRef);
        return held != null ? new Acquisition(held.toApi(), false) : new Acquisition(grant(profileId, sessionRef), true);
    }

    /** Grants a new lease, or throws if the profile is unknown or has no capacity. Runs under this object's lock. */
    protected abstract Lease grant(String profileId, String sessionRef);

    private @Nullable LeaseRecord activeLease(final String profileId, final String sessionRef) {
        synchronized (leases) {
            return leases.values().stream()
                    .filter(lease -> lease.active() && lease.profileId.equals(profileId) && lease.sessionRef.equals(sessionRef))
                    .findFirst().orElse(null);
        }
    }

    @Override
    public Lease get(final String leaseId) {
        return record(leaseId).toApi();
    }

    @Override
    public synchronized List<Lease> leases(final @Nullable String profileId, final @Nullable String sessionRef) {
        synchronized (leases) {
            return leases.values().stream().filter(LeaseRecord::active)
                    .filter(lease -> profileId == null || lease.profileId.equals(profileId))
                    .filter(lease -> sessionRef == null || sessionRef.equals(lease.sessionRef))
                    .map(LeaseRecord::toApi).toList();
        }
    }

    @Override
    public synchronized void release(final String leaseId) {
        final var lease = record(leaseId);
        if (lease.active()) {
            lease.state = LeaseState.RELEASED;
        }
    }

    @Override
    public synchronized Lease evict(final String leaseId) {
        final var lease = record(leaseId);
        if (lease.active()) {
            lease.state = LeaseState.EVICTED;
        }
        return lease.toApi();
    }
}
