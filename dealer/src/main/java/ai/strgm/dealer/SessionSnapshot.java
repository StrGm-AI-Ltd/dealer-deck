package ai.strgm.dealer;

import ai.strgm.dealer.api.model.ServerType;
import ai.strgm.dealer.api.model.SessionLeaseState;
import ai.strgm.types.ClientName;
import ai.strgm.types.LeaseId;
import ai.strgm.types.ModelName;
import ai.strgm.types.ProfileId;
import ai.strgm.types.ServerName;
import ai.strgm.types.SessionId;
import org.jspecify.annotations.Nullable;

/**
 * A session's state at one moment, as the scheduler reports it: its lease, if it holds one, its requests in flight
 * and its usage. {@code modelName} is the model of its lease, or of its last one.
 */
// TODO(#104): costUsd becomes a money amount with its currency, instead of a bare double whose currency only its name gives
public record SessionSnapshot(ClientName clientName, SessionId sessionId, @Nullable ModelName modelName, @Nullable ServerName deckServer,
        @Nullable ServerType serverType, @Nullable ProfileId profileId, @Nullable LeaseId leaseId, SessionLeaseState leaseState,
        @Nullable Long leaseAgeMs, long idleMs, int inFlight, boolean evictPending, int requests, long promptTokens, long cachedTokens,
        long completionTokens, double costUsd) {}
