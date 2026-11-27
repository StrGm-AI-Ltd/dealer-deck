package ai.strgm.dealer;

import ai.strgm.dealer.DealerProperties.ClientConfig;
import ai.strgm.types.ModelName;
import ai.strgm.types.Priority;
import ai.strgm.types.SessionId;
import java.io.InputStream;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Routes a chat completion: to a profile that serves its model, on its session's lease, through that profile's DECK server. */
@Service
public class ChatService {
    private final Scheduler scheduler;
    private final DeckRegistry decks;
    private final ObjectMapper mapper;

    public ChatService(final Scheduler scheduler, final DeckRegistry decks, final ObjectMapper mapper) {
        this.scheduler = scheduler;
        this.decks = decks;
        this.mapper = mapper;
    }

    /**
     * Places the request on a lease for its session and opens the completion on the lease's DECK server. Throws
     * {@link ModelNotFoundException} when no profile serves the model for the client, and the scheduler's and DECK's
     * exceptions before streaming starts.
     */
    public ChatStream open(final ClientConfig clientConfig, final SessionId sessionId, final ModelName modelName, final Priority priority,
            final JsonNode request) {
        // TODO(#62): say which DECK servers and model profiles the client may use, instead of its external-routing flag
        if (scheduler.candidateProfiles(clientConfig, modelName).isEmpty()) {
            throw new ModelNotFoundException("No DECK profile serves \"" + modelName + "\" for client " + clientConfig.name()
                    + (clientConfig.externalRouting() ? "" : " (external routing is off for this client)"));
        }
        final var placement = scheduler.acquire(clientConfig, sessionId, modelName, priority);
        final var upstreamRequest = (ObjectNode) request.deepCopy();
        upstreamRequest.put("model", placement.profileRef().profile().getModel().value());
        final var clientWantsUsage = request.path("stream_options").path("include_usage").isBoolean()
                && request.path("stream_options").path("include_usage").booleanValue();
        upstreamRequest.putObject("stream_options").put("include_usage", true);
        final InputStream deckEvents;
        try {
            deckEvents = decks.openChat(placement.profileRef().serverRef(), placement.leaseId(), upstreamRequest.toString());
        } catch (RuntimeException exception) {
            scheduler.release(placement, 0, 0, 0);
            throw exception;
        }
        return new ChatStream(placement, deckEvents, clientWantsUsage, scheduler, mapper);
    }
}
