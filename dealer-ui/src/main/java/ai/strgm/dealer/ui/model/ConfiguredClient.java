package ai.strgm.dealer.ui.model;

import ai.strgm.types.ClientName;
import ai.strgm.types.Priority;
import org.jspecify.annotations.Nullable;

/** A client DEALER is configured for: its priority, and whether it may be routed to external DECK servers. */
public record ConfiguredClient(@Nullable ClientName name, @Nullable Priority priority, boolean externalRouting) {}
