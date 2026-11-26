package ai.strgm.deck;

import ai.strgm.deck.api.model.DeckState;
import ai.strgm.deck.api.model.Lease;
import ai.strgm.deck.api.model.ModelProfiles;
import ai.strgm.types.LeaseId;
import ai.strgm.types.ProfileId;
import ai.strgm.types.SessionRef;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.JsonNode;

/**
 * One DECK interface, two implementations (D105), each an application of its own: the {@code deck-openai} module,
 * backed by an OpenAI-compatible provider such as Nebius Token Factory or a local oMLX server, and the
 * {@code deck-stub} module, which simulates local hardware. {@link AbstractDeckServer} holds what they share.
 */
public interface DeckServer {
    ModelProfiles modelProfiles();

    /**
     * Acquires a lease on {@code profileId} for {@code sessionRef}, idempotently: if the session already holds an
     * active lease on the profile, that lease is returned unchanged ({@code created} is false).
     */
    Acquisition acquire(ProfileId profileId, SessionRef sessionRef);

    Lease get(LeaseId leaseId);

    /** The active leases, oldest first; only those on {@code profileId} and of {@code sessionRef}, if given. */
    List<Lease> leases(@Nullable ProfileId profileId, @Nullable SessionRef sessionRef);

    void release(LeaseId leaseId);

    Lease evict(LeaseId leaseId);

    DeckState state();

    /** Streams the completion; throws {@link ApiException} or {@link UpstreamException} before streaming starts. */
    StreamingResponseBody chat(LeaseId leaseId, @Nullable String providerAuthorization, JsonNode request);
}
