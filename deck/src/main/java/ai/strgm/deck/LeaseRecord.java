package ai.strgm.deck;

import ai.strgm.deck.api.model.Lease;
import ai.strgm.deck.api.model.LeaseState;
import ai.strgm.deck.api.model.Usage;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Mutable lease bookkeeping shared by both implementations. Guarded by the owning server's lock. */
public final class LeaseRecord {
    public final String id = "lease-" + UUID.randomUUID().toString().substring(0, 8);
    public final String profileId;
    final String sessionRef;
    final long createdAt = System.currentTimeMillis();
    public volatile LeaseState state;
    public volatile @Nullable Long readyAt;
    public long promptTokens;
    public long cachedTokens;
    public long completionTokens;
    boolean estimated;

    public LeaseRecord(final String profileId, final String sessionRef, final LeaseState state) {
        this.profileId = profileId;
        this.sessionRef = sessionRef;
        this.state = state;
        if (state == LeaseState.READY) {
            readyAt = createdAt;
        }
    }

    public boolean active() {
        return state == LeaseState.LOADING || state == LeaseState.READY;
    }

    public synchronized void addUsage(final long prompt, final long cached, final long completion, final boolean est) {
        promptTokens += prompt;
        cachedTokens += cached;
        completionTokens += completion;
        estimated |= est;
    }

    public synchronized Lease toApi() {
        return new Lease(id, profileId, state).sessionRef(sessionRef).createdAtMs(createdAt).readyAtMs(readyAt)
                .usage(new Usage().promptTokens(promptTokens).cachedTokens(cachedTokens)
                        .completionTokens(completionTokens).estimated(estimated));
    }
}
