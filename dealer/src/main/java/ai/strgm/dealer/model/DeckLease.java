package ai.strgm.dealer.model;

import ai.strgm.deck.api.model.LeaseState;
import ai.strgm.types.LeaseId;
import org.jspecify.annotations.Nullable;

/** A lease a DECK server granted, as DEALER tracks it: its state, and when it became ready, once it has. */
public record DeckLease(LeaseId id, LeaseState state, @Nullable Long readyAtMs) {}
