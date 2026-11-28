package ai.strgm.deck.client.model;

import ai.strgm.deck.api.model.WorkerState;
import org.jspecify.annotations.Nullable;

/** A worker on a DECK server, as the server reports it: the profile it has loaded, if any, and its state. */
public record DeckWorker(@Nullable String id, @Nullable String loadedProfile, @Nullable WorkerState state) {}
