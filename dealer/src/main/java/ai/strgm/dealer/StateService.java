package ai.strgm.dealer;

import ai.strgm.dealer.DealerSnapshot.ServerStatus;
import org.springframework.stereotype.Service;

/** DEALER's state, as {@code /metrics/state} reports it. */
@Service
public class StateService {
    private final DeckRegistry decks;
    private final Scheduler scheduler;
    private final DealerProperties properties;

    public StateService(final DeckRegistry decks, final Scheduler scheduler, final DealerProperties properties) {
        this.decks = decks;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    public DealerSnapshot state() {
        final var servers = decks.servers().values().stream()
                .map(serverRef -> new ServerStatus(serverRef.name(), serverRef.config().url(), serverRef.config().egress(),
                        decks.reachable(serverRef.name()), (int) decks.profiles().stream()
                                .filter(profileRef -> profileRef.serverRef() == serverRef)
                                .count()))
                .toList();
        return new DealerSnapshot(properties.clients(), servers, scheduler.queue(), scheduler.sessions(), scheduler.evictions());
    }
}
