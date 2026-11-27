package ai.strgm.dealer.service;

import ai.strgm.dealer.client.DeckRegistry;
import ai.strgm.dealer.config.DealerProperties.ClientConfig;
import ai.strgm.dealer.exception.ModelNotFoundException;
import ai.strgm.deck.client.DeckChatStream;
import ai.strgm.types.ModelName;
import ai.strgm.types.Priority;
import ai.strgm.types.SessionId;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Routes a chat completion: to a profile that serves its model, on its session's lease, through that profile's DECK server. */
@Service
public class ChatService {
    private final Scheduler scheduler;
    private final DeckRegistry decks;

    public ChatService(final Scheduler scheduler, final DeckRegistry decks) {
        this.scheduler = scheduler;
        this.decks = decks;
    }

    /**
     * Places the request on a lease for its session and opens the completion on the lease's DECK server. Throws
     * {@link ModelNotFoundException} when no profile serves the model for the client, and the scheduler's and DECK's
     * exceptions before streaming starts.
     */
    public ChatRelay openStream(final ClientConfig clientConfig, final SessionId sessionId, final ModelName modelName, final Priority priority,
            final JsonNode chatRequest) {
        // TODO(#62): say which DECK servers and model profiles the client may use, instead of its external-routing flag
        if (scheduler.candidateProfiles(clientConfig, modelName).isEmpty()) {
            throw new ModelNotFoundException("No DECK profile serves \"" + modelName + "\" for client " + clientConfig.name()
                    + (clientConfig.externalRouting() ? "" : " (external routing is off for this client)"));
        }
        final var placement = scheduler.acquireLease(clientConfig, sessionId, modelName, priority);
        final var clientWantsUsage = chatRequest.path("stream_options").path("include_usage").isBoolean()
                && chatRequest.path("stream_options").path("include_usage").booleanValue();
        final DeckChatStream deckEvents;
        try {
            deckEvents = decks.openChatStream(placement.profileRef().serverRef(), placement.leaseId(), placement.profileRef().profile().modelName(),
                    chatRequest);
        } catch (RuntimeException exception) {
            scheduler.endRequest(placement, 0, 0, 0);
            throw exception;
        }
        return new ChatRelay(placement, deckEvents, clientWantsUsage, scheduler);
    }
}
