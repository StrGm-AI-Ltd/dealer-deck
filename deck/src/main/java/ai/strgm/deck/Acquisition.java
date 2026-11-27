package ai.strgm.deck;

/** The result of {@link DeckServer#acquire}: the lease, and whether it was granted now or already held. */
public record Acquisition(LeaseSnapshot lease, boolean created) {}
