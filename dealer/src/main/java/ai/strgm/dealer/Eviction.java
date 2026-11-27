package ai.strgm.dealer;

import ai.strgm.types.ClientName;
import ai.strgm.types.ProfileId;
import ai.strgm.types.ServerName;
import ai.strgm.types.SessionId;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** An eviction the scheduler started: whose lease, on which server and profile, for which client, and why. */
public record Eviction(Instant createdAt, ClientName victimClient, SessionId victimSession, ServerName deckServer,
        @Nullable ProfileId profileId, ClientName byClient, String reason) {}
