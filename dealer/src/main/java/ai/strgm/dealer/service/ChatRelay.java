package ai.strgm.dealer.service;

import static java.nio.charset.StandardCharsets.UTF_8;

import ai.strgm.dealer.model.RequestPlacement;
import ai.strgm.deck.client.DeckChatStream;
import ai.strgm.deck.client.model.DeckChatEvent;
import ai.strgm.types.ProfileId;
import ai.strgm.types.ServerName;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Relays a chat completion from DECK to the client: it passes on DECK's server-sent events, drops the usage-only chunk
 * the client didn't ask for, and ends the request on its lease with the usage DECK reported.
 */
public final class ChatRelay {
    private final RequestPlacement placement;
    private final DeckChatStream deckEvents;
    private final boolean clientWantsUsage;
    private final Scheduler scheduler;

    ChatRelay(final RequestPlacement placement, final DeckChatStream deckEvents, final boolean clientWantsUsage, final Scheduler scheduler) {
        this.placement = placement;
        this.deckEvents = deckEvents;
        this.clientWantsUsage = clientWantsUsage;
        this.scheduler = scheduler;
    }

    /** The DECK server serving the completion. */
    public ServerName serverName() {
        return placement.profileRef().serverRef().name();
    }

    /** The model profile serving the completion. */
    public ProfileId profileId() {
        return placement.profileRef().profile().id();
    }

    /** Copies DECK's events to {@code out} until DECK ends the stream, then ends the request on its lease. */
    public void relayTo(final OutputStream out) throws IOException {
        long promptTokens = 0;
        long cachedTokens = 0;
        long completionTokens = 0;
        try (deckEvents) {
            DeckChatEvent event;
            while ((event = deckEvents.nextEvent()) != null) {
                if (event.usage() != null) {
                    promptTokens = event.usage().promptTokens();
                    cachedTokens = event.usage().cachedTokens();
                    completionTokens = event.usage().completionTokens();
                }
                if (event.usageOnly() && !clientWantsUsage) {
                    continue;
                }
                out.write(event.text().getBytes(UTF_8));
                out.flush();
            }
        } finally {
            scheduler.endRequest(placement, promptTokens, cachedTokens, completionTokens);
        }
    }
}
