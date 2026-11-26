package ai.strgm.deck;

import ai.strgm.deck.api.model.Lease;

/** The result of {@link DeckServer#acquire}: the lease, and whether it was granted now or already held. */
public record Acquisition(Lease lease, boolean created) {}
