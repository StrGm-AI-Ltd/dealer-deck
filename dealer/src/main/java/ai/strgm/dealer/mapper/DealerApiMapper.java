package ai.strgm.dealer.mapper;

import static ai.strgm.util.Nulls.mapNullable;

import ai.strgm.dealer.config.DealerProperties.ClientConfig;
import ai.strgm.dealer.model.DealerSnapshot;
import ai.strgm.dealer.model.DealerSnapshot.ServerStatus;
import ai.strgm.dealer.api.model.ClientInfo;
import ai.strgm.dealer.api.model.DealerState;
import ai.strgm.dealer.api.model.ErrorBody;
import ai.strgm.dealer.api.model.ErrorResponse;
import ai.strgm.dealer.api.model.EvictionEvent;
import ai.strgm.dealer.api.model.Model;
import ai.strgm.dealer.api.model.ModelList;
import ai.strgm.dealer.api.model.QueuedRequest;
import ai.strgm.dealer.api.model.ServerInfo;
import ai.strgm.dealer.api.model.SessionInfo;
import ai.strgm.dealer.model.Eviction;
import ai.strgm.dealer.model.LeaseRequestSnapshot;
import ai.strgm.dealer.model.ListedModel;
import ai.strgm.dealer.model.SessionSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Maps DEALER's domain objects to the models generated from the DEALER OpenAPI document (D144). */
public final class DealerApiMapper {
    private DealerApiMapper() {
    }

    /** {@code createdAt}: every model's {@code created}. */
    public static ModelList modelList(final List<ListedModel> listedModels, final Instant createdAt) {
        final var models = listedModels.stream()
                .map(listedModel -> new Model(listedModel.modelName(), "model", createdAt.getEpochSecond(), listedModel.ownedBy())
                        .deckServer(listedModel.deckServer()).serverType(listedModel.serverType())
                        .contextWindow(listedModel.contextWindow()).virtual(listedModel.virtual()))
                .toList();
        return new ModelList("list", models);
    }

    public static DealerState dealerState(final DealerSnapshot snapshot) {
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

    /** The documented error body; {@code param} names the offending field or header, if any. */
    public static ErrorResponse errorResponse(final String message, final String type, final @Nullable String param) {
        return new ErrorResponse(new ErrorBody(message, type).param(param));
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
                .waitingMs(leaseRequest.waiting().toMillis()).reservedProfile(leaseRequest.reservedProfile());
    }

    private static SessionInfo sessionInfo(final SessionSnapshot session) {
        return new SessionInfo().client(session.clientName()).sessionId(session.sessionId()).model(session.modelName())
                .deckServer(session.deckServer()).serverType(session.serverType()).profileId(session.profileId()).leaseId(session.leaseId())
                .leaseState(session.leaseState()).leaseAgeMs(mapNullable(session.leaseAge(), Duration::toMillis))
                .idleMs(session.idle().toMillis()).inFlight(session.inFlight())
                .evictPending(session.evictPending()).requests(session.requests()).promptTokens(session.promptTokens())
                .cachedTokens(session.cachedTokens()).completionTokens(session.completionTokens()).costUsd(session.costUsd());
    }

    private static EvictionEvent evictionEvent(final Eviction eviction) {
        return new EvictionEvent().createdAt(eviction.createdAt().getEpochSecond()).victimClient(eviction.victimClient())
                .victimSession(eviction.victimSession()).deckServer(eviction.deckServer()).profileId(eviction.profileId())
                .byClient(eviction.byClient()).reason(eviction.reason());
    }
}
