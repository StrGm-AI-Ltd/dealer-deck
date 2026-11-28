package ai.strgm.deck.service;

import static java.lang.Math.max;

import ai.strgm.deck.api.model.LeaseState;
import ai.strgm.deck.config.DeckProperties;
import ai.strgm.deck.exception.LeaseInactiveException;
import ai.strgm.deck.exception.UnknownLeaseException;
import ai.strgm.deck.model.LeaseAcquisition;
import ai.strgm.deck.model.LeaseRecord;
import ai.strgm.deck.model.LeaseSnapshot;
import ai.strgm.types.LeaseId;
import ai.strgm.types.ProfileId;
import ai.strgm.types.SessionRef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * What both DECK implementations share: the lease store, and the lease operations that don't depend on how a
 * server manages its capacity. One lock, {@link #lock}, guards the store, every lease's state and usage, and a
 * server's own capacity state, so a capacity check and the grant it allows happen together.
 */
public abstract class AbstractDeckServer implements DeckServer {
    protected final DeckProperties properties;
    protected final ObjectMapper mapper;
    /**
     * Guards the lease store and a subclass's own capacity state (e.g. which profile each simulated worker has
     * loaded). A private object rather than this server's monitor, which any holder of the bean could lock.
     */
    protected final Object lock = new Object();
    /** Every lease this server has granted, oldest first. */
    // TODO(#103): keep the leases in a persistent store, so a DECK restart doesn't lose them
    private final Map<LeaseId, LeaseRecord> leases = new LinkedHashMap<>();

    protected AbstractDeckServer(final DeckProperties properties, final ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    private LeaseRecord leaseRecord(final LeaseId leaseId) {
        final var leaseRecord = leases.get(leaseId);
        if (leaseRecord == null) {
            throw new UnknownLeaseException(leaseId);
        }
        return leaseRecord;
    }

    /** The lease a chat request names, which must still be active. */
    protected LeaseRecord activeLeaseRecord(final LeaseId leaseId) {
        synchronized (lock) {
            final var leaseRecord = leaseRecord(leaseId);
            if (!leaseRecord.active()) {
                throw new LeaseInactiveException(leaseId, leaseRecord.state());
            }
            return leaseRecord;
        }
    }

    /** The active leases, oldest first. Their ids and profiles are safe to read without the lock: they never change. */
    protected List<LeaseRecord> activeLeaseRecords() {
        synchronized (lock) {
            return leases.values().stream()
                    .filter(LeaseRecord::active)
                    .toList();
        }
    }

    /** The ids of the active leases on {@code profileId}. */
    protected List<LeaseId> activeLeaseIds(final ProfileId profileId) {
        synchronized (lock) {
            return leases.values().stream()
                    .filter(leaseRecord -> leaseRecord.active() && leaseRecord.profileId().equals(profileId))
                    .map(LeaseRecord::id)
                    .toList();
        }
    }

    /** Stores a new lease. For {@link #grantLease}, which runs under the lock. */
    protected LeaseRecord registerLease(final ProfileId profileId, final SessionRef sessionRef, final LeaseState state) {
        synchronized (lock) {
            final var leaseRecord = new LeaseRecord(profileId, sessionRef, state);
            leases.put(leaseRecord.id(), leaseRecord);
            return leaseRecord;
        }
    }

    /** A loading lease becomes ready, e.g. once a simulated load finishes. */
    protected void markReady(final LeaseRecord leaseRecord) {
        synchronized (lock) {
            leaseRecord.markReady();
        }
    }

    protected boolean isLoading(final LeaseRecord leaseRecord) {
        synchronized (lock) {
            return leaseRecord.state() == LeaseState.LOADING;
        }
    }

    /** Adds a completion's tokens to its lease; {@code estimated} when the provider sent no usage. */
    protected void addUsage(final LeaseRecord leaseRecord, final long promptTokens, final long cachedTokens, final long completionTokens,
            final boolean estimated) {
        synchronized (lock) {
            leaseRecord.addUsage(promptTokens, cachedTokens, completionTokens, estimated);
        }
    }

    /** Every lease, oldest first. */
    protected List<LeaseSnapshot> allLeases() {
        synchronized (lock) {
            return leases.values().stream()
                    .map(LeaseRecord::snapshot)
                    .toList();
        }
    }

    /** The most recent leases ({@code deck.recent-leases}), oldest first, for {@link #state()}. */
    protected List<LeaseSnapshot> recentLeases() {
        final var allLeases = allLeases();
        return allLeases.subList(max(0, allLeases.size() - properties.recentLeases()), allLeases.size());
    }

    /**
     * Idempotent per session and profile: a session that already holds an active lease on the profile gets that
     * lease back, so retrying an acquisition whose response was lost doesn't grant a second lease, which nothing
     * would ever release (#65).
     */
    @Override
    public final LeaseAcquisition acquireLease(final ProfileId profileId, final SessionRef sessionRef) {
        synchronized (lock) {
            final var heldLeaseRecord = leases.values().stream()
                    .filter(leaseRecord -> leaseRecord.active() && leaseRecord.profileId().equals(profileId)
                            && leaseRecord.sessionRef().equals(sessionRef))
                    .findFirst();
            return heldLeaseRecord.isPresent() ? new LeaseAcquisition(heldLeaseRecord.get().snapshot(), false)
                    : new LeaseAcquisition(grantLease(profileId, sessionRef).snapshot(), true);
        }
    }

    /**
     * Grants a new lease with {@link #registerLease}, or throws if the profile is unknown or has no capacity. Runs under
     * the lock, so the capacity it checks can't change before the lease is stored.
     */
    protected abstract LeaseRecord grantLease(ProfileId profileId, SessionRef sessionRef);

    @Override
    public LeaseSnapshot lease(final LeaseId leaseId) {
        synchronized (lock) {
            return leaseRecord(leaseId).snapshot();
        }
    }

    @Override
    public List<LeaseSnapshot> leases(final @Nullable ProfileId profileId, final @Nullable SessionRef sessionRef) {
        synchronized (lock) {
            return leases.values().stream()
                    .filter(LeaseRecord::active)
                    .filter(leaseRecord -> profileId == null || leaseRecord.profileId().equals(profileId))
                    .filter(leaseRecord -> sessionRef == null || sessionRef.equals(leaseRecord.sessionRef()))
                    .map(LeaseRecord::snapshot)
                    .toList();
        }
    }

    @Override
    public void releaseLease(final LeaseId leaseId) {
        synchronized (lock) {
            leaseRecord(leaseId).end(LeaseState.RELEASED);
        }
    }

    @Override
    public LeaseSnapshot evictLease(final LeaseId leaseId) {
        synchronized (lock) {
            final var leaseRecord = leaseRecord(leaseId);
            leaseRecord.end(LeaseState.EVICTED);
            return leaseRecord.snapshot();
        }
    }
}
