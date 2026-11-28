package ai.strgm.dealer.ui.model;

import ai.strgm.types.ClientName;
import ai.strgm.types.ProfileId;
import ai.strgm.types.ServerName;
import ai.strgm.types.SessionId;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** An eviction: when, whose session lost its lease on which DECK server and profile, for which client, and why. */
public record Eviction(@Nullable Instant createdAt, @Nullable ClientName victimClient, @Nullable SessionId victimSession, @Nullable ServerName deckServer,
        @Nullable ProfileId profileId, @Nullable ClientName byClient, @Nullable String reason) {}
