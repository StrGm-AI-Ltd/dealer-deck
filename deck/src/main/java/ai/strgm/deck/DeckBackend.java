package ai.strgm.deck;

import ai.strgm.deck.api.model.DeckState;
import ai.strgm.deck.api.model.Lease;
import ai.strgm.deck.api.model.ModelProfiles;
import java.util.List;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.JsonNode;

/** One DECK interface, two implementations: Nebius Token Factory and the local stub (D105). */
public interface DeckBackend {
    ModelProfiles modelProfiles();

    Lease acquire(String profileId, String sessionRef);

    Lease get(String leaseId);

    /** The active leases, oldest first; only those on {@code profileId} unless it's null. */
    List<Lease> leases(String profileId);

    void release(String leaseId);

    Lease evict(String leaseId);

    DeckState state();

    /** Streams the completion; throws {@link ApiException} or {@link UpstreamException} before streaming starts. */
    StreamingResponseBody chat(String leaseId, String providerAuthorization, JsonNode request);
}
