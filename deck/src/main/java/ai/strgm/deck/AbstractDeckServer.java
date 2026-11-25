package ai.strgm.deck;

import ai.strgm.deck.api.model.Lease;
import ai.strgm.deck.api.model.LeaseState;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

/**
 * What both DECK implementations share: the lease store, and the lease operations that don't depend on how a
 * server manages its capacity. A lease's state changes only under this object's lock.
 */
public abstract class AbstractDeckServer implements DeckServer {
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
        final var l = leases.get(leaseId);
        if (l == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "Unknown lease: " + leaseId);
        }
        return l;
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
            return leases.values().stream().filter(l -> l.active() && l.profileId.equals(profileId)).map(l -> l.id).toList();
        }
    }

    /** Every lease, oldest first. */
    protected List<LeaseRecord> all() {
        synchronized (leases) {
            return List.copyOf(leases.values());
        }
    }

    /** The 30 most recent leases, oldest first, for {@link #state()}. */
    protected List<Lease> recent() {
        final var all = all();
        return all.subList(Math.max(0, all.size() - 30), all.size()).stream().map(LeaseRecord::toApi).toList();
    }

    @Override
    public Lease get(final String leaseId) {
        return record(leaseId).toApi();
    }

    @Override
    public synchronized List<Lease> leases(final String profileId) {
        synchronized (leases) {
            return leases.values().stream().filter(LeaseRecord::active)
                    .filter(l -> profileId == null || l.profileId.equals(profileId)).map(LeaseRecord::toApi).toList();
        }
    }

    @Override
    public synchronized void release(final String leaseId) {
        final var l = record(leaseId);
        if (l.active()) {
            l.state = LeaseState.RELEASED;
        }
    }

    @Override
    public synchronized Lease evict(final String leaseId) {
        final var l = record(leaseId);
        if (l.active()) {
            l.state = LeaseState.EVICTED;
        }
        return l.toApi();
    }
}
