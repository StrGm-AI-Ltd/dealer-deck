package ai.strgm.deck.model;


/** The result of {@code DeckServer.acquireLease()}: the lease, and whether it was granted now or already held. */
public record LeaseAcquisition(LeaseSnapshot lease, boolean created) {}
