package ai.strgm.deck.model;

import ai.strgm.deck.api.model.LeaseState;
import ai.strgm.types.LeaseId;
import ai.strgm.types.ProfileId;
import ai.strgm.types.SessionRef;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** A lease at one moment: its state, when it was created and became ready, and its session's usage on it so far. */
public record LeaseSnapshot(LeaseId id, ProfileId profileId, LeaseState state, SessionRef sessionRef, Instant createdAt,
        @Nullable Instant readyAt, TokenUsage usage) {

    /** Tokens used on a lease; {@code estimated} when the provider sent no usage for some completion. */
    public record TokenUsage(long promptTokens, long cachedTokens, long completionTokens, boolean estimated) {}
}
