package ai.strgm.deck.model;

import static java.lang.System.currentTimeMillis;
import static java.util.UUID.randomUUID;

import ai.strgm.deck.model.LeaseSnapshot.TokenUsage;
import ai.strgm.deck.api.model.LeaseState;
import ai.strgm.types.LeaseId;
import ai.strgm.types.ProfileId;
import ai.strgm.types.SessionRef;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A lease a DECK server has granted. Its id, profile and session never change. Its state and usage do, and only
 * {@code AbstractDeckServer} changes or reads them, under its lock: this class isn't thread-safe on its own.
 */
public final class LeaseRecord {
    /** How many of a random UUID's characters a lease id keeps. */
    private static final int ID_LENGTH = 8;

    private final LeaseId id = new LeaseId("lease-" + randomUUID().toString().substring(0, ID_LENGTH));
    private final ProfileId profileId;
    private final SessionRef sessionRef;
    private final long createdAt = currentTimeMillis();
    private LeaseState state;
    private @Nullable Long readyAt;
    private long promptTokens;
    private long cachedTokens;
    private long completionTokens;
    private boolean estimated;

    public LeaseRecord(final ProfileId profileId, final SessionRef sessionRef, final LeaseState state) {
        this.profileId = profileId;
        this.sessionRef = sessionRef;
        this.state = state;
        if (state == LeaseState.READY) {
            readyAt = createdAt;
        }
    }

    public LeaseId id() {
        return id;
    }

    public ProfileId profileId() {
        return profileId;
    }

    public SessionRef sessionRef() {
        return sessionRef;
    }

    public LeaseState state() {
        return state;
    }

    public boolean active() {
        return state == LeaseState.LOADING || state == LeaseState.READY;
    }

    /** A loading lease becomes ready; a lease released or evicted while it loaded stays so. */
    public void markReady() {
        if (state == LeaseState.LOADING) {
            readyAt = currentTimeMillis();
            state = LeaseState.READY;
        }
    }

    /** Ends an active lease as {@code endState} (released or evicted); an inactive one stays as it is. */
    public void end(final LeaseState endState) {
        if (active()) {
            state = endState;
        }
    }

    public void addUsage(final long promptTokens, final long cachedTokens, final long completionTokens, final boolean usageEstimated) {
        this.promptTokens += promptTokens;
        this.cachedTokens += cachedTokens;
        this.completionTokens += completionTokens;
        estimated |= usageEstimated;
    }

    /** The lease as it is now, for reading outside the lock. */
    public LeaseSnapshot snapshot() {
        return new LeaseSnapshot(id, profileId, state, sessionRef, createdAt, readyAt,
                new TokenUsage(promptTokens, cachedTokens, completionTokens, estimated));
    }
}
