package ai.strgm.dealer.ui.model;

import ai.strgm.dealer.api.model.ServerType;
import ai.strgm.dealer.api.model.SessionLeaseState;
import ai.strgm.types.ClientName;
import ai.strgm.types.ModelName;
import ai.strgm.types.ProfileId;
import ai.strgm.types.ServerName;
import ai.strgm.types.SessionId;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * A session: its client and model, the DECK server and profile of its lease, if it holds one, the lease's state and
 * age, how long the session has been idle, and its requests, tokens and cost.
 */
// TODO(#104): money amounts with their currency, instead of a bare double in an implied currency
public record SessionSnapshot(@Nullable ClientName client, @Nullable SessionId sessionId, @Nullable ModelName model, @Nullable ServerName deckServer,
        @Nullable ServerType serverType, @Nullable ProfileId profileId, @Nullable SessionLeaseState leaseState, @Nullable Duration leaseAge,
        @Nullable Duration idle, int requests, long promptTokens, long cachedTokens, long completionTokens, double costUsd) {}
