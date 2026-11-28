package ai.strgm.dealer.model;

import ai.strgm.types.ClientName;
import ai.strgm.types.ModelName;
import ai.strgm.types.Priority;
import ai.strgm.types.SessionId;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * A queued lease request at one moment: whose it is, what it asks for, how long it has waited, and the profile being
 * drained for it ({@code server/profile}), if any.
 */
public record LeaseRequestSnapshot(ClientName clientName, SessionId sessionId, ModelName modelName, Priority clientPriority,
        Priority requestPriority, Duration waiting, @Nullable String reservedProfile) {}
