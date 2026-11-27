package ai.strgm.dealer;

import ai.strgm.dealer.api.model.ClientInfo;
import ai.strgm.dealer.api.model.DealerState;
import ai.strgm.dealer.api.model.Model;
import ai.strgm.dealer.api.model.ModelList;
import ai.strgm.dealer.api.model.ServerInfo;
import ai.strgm.dealer.api.server.MetricsApi;
import ai.strgm.dealer.api.server.ModelsApi;
import ai.strgm.types.ModelName;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Implements the interfaces generated from the DEALER OpenAPI document (D99, D102). */
@RestController
public class DealerApiController implements ModelsApi, MetricsApi {
    private final DeckRegistry decks;
    private final Scheduler scheduler;
    private final DealerProperties properties;
    // every model's `created`: DEALER's start time (this controller is created once, at startup), as no model has
    // a creation date of its own
    private final long startedAt = Instant.now().getEpochSecond();

    public DealerApiController(final DeckRegistry decks, final Scheduler scheduler, final DealerProperties properties) {
        this.decks = decks;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    @Override
    public ResponseEntity<ModelList> listModels() {
        final List<Model> data = new ArrayList<>();
        for (final var tier : AutoTier.ALL) {
            data.add(new Model(tier.modelName(), "model", startedAt, "dealer").contextWindow(tier.contextWindow()).virtual(true));
        }
        // TODO(#62): list only the profiles the requesting client may use, not every profile for every client
        // a profile is listed by its id, which clients may request as a model name
        decks.profiles().forEach(profileRef -> data.add(new Model(new ModelName(profileRef.profile().getId().value()), "model", startedAt,
                profileRef.serverRef().name().value()).deckServer(profileRef.serverRef().name())
                .serverType(profileRef.serverRef().config().egress())
                .contextWindow(profileRef.profile().getContextWindow()).virtual(false)));
        return ResponseEntity.ok(new ModelList("list", data));
    }

    @Override
    public ResponseEntity<DealerState> getDealerState() {
        // TODO(#62): report each client's DECK servers and model profiles instead of its external-routing flag
        final var clients = properties.clients().stream()
                .map(clientConfig -> new ClientInfo().name(clientConfig.name()).priority(clientConfig.priority()).externalRouting(clientConfig.externalRouting()))
                .toList();
        final var servers = decks.servers().values().stream()
                .map(serverRef -> new ServerInfo().name(serverRef.name()).url(serverRef.config().url())
                        .serverType(serverRef.config().egress()).reachable(decks.reachable(serverRef.name()))
                        .profiles((int) decks.profiles().stream()
                                .filter(profileRef -> profileRef.serverRef() == serverRef)
                                .count()))
                .toList();
        return ResponseEntity.ok(new DealerState().clients(clients).servers(servers).queue(scheduler.queue())
                .sessions(scheduler.sessions()).evictions(scheduler.evictions()));
    }
}
