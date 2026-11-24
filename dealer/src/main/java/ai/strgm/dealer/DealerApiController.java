package ai.strgm.dealer;

import ai.strgm.dealer.api.model.ClientInfo;
import ai.strgm.dealer.api.model.DealerState;
import ai.strgm.dealer.api.model.Model;
import ai.strgm.dealer.api.model.ModelList;
import ai.strgm.dealer.api.model.ServerInfo;
import ai.strgm.dealer.api.model.ServerType;
import ai.strgm.dealer.api.server.MetricsApi;
import ai.strgm.dealer.api.server.ModelsApi;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Implements the interfaces generated from the DEALER OpenAPI document (D99, D102). */
@RestController
public class DealerApiController implements ModelsApi, MetricsApi {
    private static final List<Integer> TIERS = List.of(32768, 65536, 131072);
    private final DeckRegistry decks;
    private final Scheduler scheduler;
    private final DealerProperties props;
    // every model's `created`: DEALER's start time (this controller is created once, at startup), as no model has
    // a creation date of its own
    private final long startedAt = Instant.now().getEpochSecond();

    public DealerApiController(final DeckRegistry decks, final Scheduler scheduler, final DealerProperties props) {
        this.decks = decks;
        this.scheduler = scheduler;
        this.props = props;
    }

    @Override
    public ResponseEntity<ModelList> listModels() {
        final List<Model> data = new ArrayList<>();
        for (final var window : TIERS) {
            data.add(new Model("auto-" + (window / 1024 == 64 ? 65 : window / 1024) + "k", "model", startedAt, "dealer")
                    .contextWindow(window).virtual(true));
        }
        decks.profiles().forEach(p -> data.add(new Model(p.profile().getId(), "model", startedAt, p.server().name())
                .deckServer(p.server().name()).serverType(ServerType.fromValue(p.server().def().egress()))
                .contextWindow(p.profile().getContextWindow()).virtual(false)));
        return ResponseEntity.ok(new ModelList("list", data));
    }

    @Override
    public ResponseEntity<DealerState> getDealerState() {
        final var clients = props.clients().stream()
                .map(c -> new ClientInfo().name(c.name()).priority(c.priority()).externalRouting(c.externalRouting())).toList();
        final var servers = decks.servers().values().stream()
                .map(s -> new ServerInfo().name(s.name()).url(s.def().url()).serverType(ServerType.fromValue(s.def().egress()))
                        .reachable(decks.reachable(s.name()))
                        .profiles((int) decks.profiles().stream().filter(p -> p.server() == s).count()))
                .toList();
        return ResponseEntity.ok(new DealerState().clients(clients).servers(servers).queue(scheduler.queue())
                .sessions(scheduler.sessions()).evictions(scheduler.evictions()));
    }
}
