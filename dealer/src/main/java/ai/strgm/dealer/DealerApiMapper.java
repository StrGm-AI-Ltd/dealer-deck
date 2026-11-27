package ai.strgm.dealer;

import ai.strgm.dealer.DealerProperties.ClientConfig;
import ai.strgm.dealer.DealerSnapshot.ServerStatus;
import ai.strgm.dealer.api.model.ClientInfo;
import ai.strgm.dealer.api.model.DealerState;
import ai.strgm.dealer.api.model.EvictionEvent;
import ai.strgm.dealer.api.model.Model;
import ai.strgm.dealer.api.model.ModelList;
import ai.strgm.dealer.api.model.QueuedRequest;
import ai.strgm.dealer.api.model.ServerInfo;
import ai.strgm.dealer.api.model.SessionInfo;
import java.util.List;

/** Maps DEALER's domain objects to the models generated from the DEALER OpenAPI document (D144). */
final class DealerApiMapper {
    private DealerApiMapper() {
    }

    /** {@code createdAt}: every model's {@code created}, in epoch seconds. */
    static ModelList modelList(final List<ListedModel> listedModels, final long createdAt) {
        final var models = listedModels.stream()
                .map(listedModel -> new Model(listedModel.modelName(), "model", createdAt, listedModel.ownedBy())
                        .deckServer(listedModel.deckServer()).serverType(listedModel.serverType())
                        .contextWindow(listedModel.contextWindow()).virtual(listedModel.virtual()))
                .toList();
        return new ModelList("list", models);
    }

    static DealerState dealerState(final DealerSnapshot snapshot) {
        return new DealerState()
                .clients(snapshot.clients().stream()
                        .map(DealerApiMapper::clientInfo)
                        .toList())
                .servers(snapshot.servers().stream()
                        .map(DealerApiMapper::serverInfo)
                        .toList())
                .queue(snapshot.queue().stream()
                        .map(DealerApiMapper::queuedRequest)
                        .toList())
                .sessions(snapshot.sessions().stream()
                        .map(DealerApiMapper::sessionInfo)
                        .toList())
                .evictions(snapshot.evictions().stream()
                        .map(DealerApiMapper::evictionEvent)
                        .toList());
    }

    // TODO(#62): report each client's DECK servers and model profiles instead of its external-routing flag
    private static ClientInfo clientInfo(final ClientConfig clientConfig) {
        return new ClientInfo().name(clientConfig.name()).priority(clientConfig.priority()).externalRouting(clientConfig.externalRouting());
    }

    private static ServerInfo serverInfo(final ServerStatus serverStatus) {
        return new ServerInfo().name(serverStatus.serverName()).url(serverStatus.url()).serverType(serverStatus.serverType())
                .reachable(serverStatus.reachable()).profiles(serverStatus.profiles());
    }

    private static QueuedRequest queuedRequest(final LeaseRequestSnapshot leaseRequest) {
        return new QueuedRequest().client(leaseRequest.clientName()).sessionId(leaseRequest.sessionId()).model(leaseRequest.modelName())
                .clientPriority(leaseRequest.clientPriority()).requestPriority(leaseRequest.requestPriority())
                .waitingMs(leaseRequest.waitingMs()).reservedProfile(leaseRequest.reservedProfile());
    }

    private static SessionInfo sessionInfo(final SessionSnapshot session) {
        return new SessionInfo().client(session.clientName()).sessionId(session.sessionId()).model(session.modelName())
                .deckServer(session.deckServer()).serverType(session.serverType()).profileId(session.profileId()).leaseId(session.leaseId())
                .leaseState(session.leaseState()).leaseAgeMs(session.leaseAgeMs()).idleMs(session.idleMs()).inFlight(session.inFlight())
                .evictPending(session.evictPending()).requests(session.requests()).promptTokens(session.promptTokens())
                .cachedTokens(session.cachedTokens()).completionTokens(session.completionTokens()).costUsd(session.costUsd());
    }

    private static EvictionEvent evictionEvent(final Eviction eviction) {
        return new EvictionEvent().createdAt(eviction.createdAt().getEpochSecond()).victimClient(eviction.victimClient())
                .victimSession(eviction.victimSession()).deckServer(eviction.deckServer()).profileId(eviction.profileId())
                .byClient(eviction.byClient()).reason(eviction.reason());
    }
}
