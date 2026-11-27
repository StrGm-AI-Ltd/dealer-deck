package ai.strgm.deck.service;

import ai.strgm.deck.model.LeaseAcquisition;
import ai.strgm.deck.model.LeaseSnapshot;
import ai.strgm.deck.model.OfferedProfiles;
import ai.strgm.deck.model.ServerState;
import ai.strgm.types.LeaseId;
import ai.strgm.types.ProfileId;
import ai.strgm.types.SessionRef;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * One DECK interface, two implementations (D105), each an application of its own: the {@code deck-openai} module,
 * backed by an OpenAI-compatible provider such as Nebius Token Factory or a local oMLX server, and the
 * {@code deck-stub} module, which simulates local hardware. {@link AbstractDeckServer} holds what they share.
 */
public interface DeckServer {
    OfferedProfiles modelProfiles();

    /**
     * Acquires a lease on {@code profileId} for {@code sessionRef}, idempotently: if the session already holds an
     * active lease on the profile, that lease is returned unchanged ({@code created} is false).
     */
    LeaseAcquisition acquireLease(ProfileId profileId, SessionRef sessionRef);

    LeaseSnapshot lease(LeaseId leaseId);

    /** The active leases, oldest first; only those on {@code profileId} and of {@code sessionRef}, if given. */
    List<LeaseSnapshot> leases(@Nullable ProfileId profileId, @Nullable SessionRef sessionRef);

    void releaseLease(LeaseId leaseId);

    LeaseSnapshot evictLease(LeaseId leaseId);

    ServerState state();

    /**
     * Opens the completion on lease {@code leaseId}, with the provider credential to forward, if the server needs one.
     * Throws before streaming starts if the lease isn't active or the provider refuses the request.
     */
    ChatStream openChatStream(LeaseId leaseId, @Nullable String providerAuthorization, JsonNode request);
}
