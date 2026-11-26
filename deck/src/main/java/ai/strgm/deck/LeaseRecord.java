package ai.strgm.deck;

import ai.strgm.deck.api.model.Lease;
import ai.strgm.deck.api.model.LeaseState;
import ai.strgm.deck.api.model.Usage;
import ai.strgm.types.LeaseId;
import ai.strgm.types.ProfileId;
import ai.strgm.types.SessionRef;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A lease a DECK server has granted. Its id, profile and session never change. Its state and usage do, and only
 * {@link AbstractDeckServer} changes or reads them, under its lock: this class isn't thread-safe on its own.
 */
public final class LeaseRecord {
    /** How many of a random UUID's characters a lease id keeps. */
    private static final int ID_LENGTH = 8;

    private final LeaseId id = new LeaseId("lease-" + UUID.randomUUID().toString().substring(0, ID_LENGTH));
    private final ProfileId profileId;
    private final SessionRef sessionRef;
    private final long createdAt = System.currentTimeMillis();
    private LeaseState state;
    private @Nullable Long readyAt;
    private long promptTokens;
    private long cachedTokens;
    private long completionTokens;
    private boolean estimated;

    LeaseRecord(final ProfileId profileId, final SessionRef sessionRef, final LeaseState state) {
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

    SessionRef sessionRef() {
        return sessionRef;
    }

    LeaseState state() {
        return state;
    }

    boolean active() {
        return state == LeaseState.LOADING || state == LeaseState.READY;
    }

    /** A loading lease becomes ready; a lease released or evicted while it loaded stays so. */
    void markReady() {
        if (state == LeaseState.LOADING) {
            readyAt = System.currentTimeMillis();
            state = LeaseState.READY;
        }
    }

    /** Ends an active lease as {@code ended} (released or evicted); an inactive one stays as it is. */
    void end(final LeaseState ended) {
        if (active()) {
            state = ended;
        }
    }

    void addUsage(final long prompt, final long cached, final long completion, final boolean usageEstimated) {
        promptTokens += prompt;
        cachedTokens += cached;
        completionTokens += completion;
        estimated |= usageEstimated;
    }

    Lease toApi() {
        return new Lease(id, profileId, state).sessionRef(sessionRef).createdAtMs(createdAt).readyAtMs(readyAt)
                .usage(new Usage().promptTokens(promptTokens).cachedTokens(cachedTokens)
                        .completionTokens(completionTokens).estimated(estimated));
    }
}
