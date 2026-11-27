package ai.strgm.deck.client.model;

import ai.strgm.deck.api.model.Implementation;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A DECK server's state: what it is, its provider, if it has one, what it has spent, and its profiles and workers (a
 * server without workers has none).
 */
// TODO(#104): money amounts with their currency, instead of a bare double in an implied currency
public record DeckServerState(Implementation implementation, @Nullable String provider, double spendUsd, List<DeckProfileState> profiles,
        List<DeckWorker> workers) {}
