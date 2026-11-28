package ai.strgm.dealer.ui.model;

import ai.strgm.types.ClientName;
import ai.strgm.types.ModelName;
import ai.strgm.types.Priority;
import ai.strgm.types.SessionId;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/** A lease request in DEALER's queue: whose, for which model, its priorities, how long it has waited, and the profile reserved for it while capacity drains, if any. */
public record LeaseRequestSnapshot(@Nullable ClientName client, @Nullable SessionId sessionId, @Nullable ModelName model, @Nullable Priority clientPriority,
        @Nullable Priority requestPriority, @Nullable Duration waiting, @Nullable String reservedProfile) {}
