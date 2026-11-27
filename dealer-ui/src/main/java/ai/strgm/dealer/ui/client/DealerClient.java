package ai.strgm.dealer.ui.client;

import static ai.strgm.util.Nulls.mapNullable;
import static java.util.Objects.requireNonNull;

import ai.strgm.dealer.api.client.MetricsApi;
import ai.strgm.dealer.api.model.EvictionEvent;
import ai.strgm.dealer.api.model.QueuedRequest;
import ai.strgm.dealer.api.model.SessionInfo;
import ai.strgm.dealer.ui.config.UiProperties;
import ai.strgm.dealer.ui.model.ConfiguredClient;
import ai.strgm.dealer.ui.model.DealerSnapshot;
import ai.strgm.dealer.ui.model.Eviction;
import ai.strgm.dealer.ui.model.LeaseRequestSnapshot;
import ai.strgm.dealer.ui.model.SessionSnapshot;
import ai.strgm.util.AuthorizationHeaders;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * A client of DEALER's metrics API, built on the HTTP interface generated from the DEALER OpenAPI document. It returns
 * DEALER's state as the DEALER UI's own records, never the generated models.
 */
@Component
public class DealerClient {
    private final MetricsApi metricsApi;

    public DealerClient(final UiProperties properties) {
        final var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()));
        requests.setReadTimeout(Duration.ofSeconds(properties.readTimeoutSeconds()));
        final var restClient = RestClient.builder().requestFactory(requests).baseUrl(properties.dealer().url())
                .defaultHeader("Authorization", AuthorizationHeaders.forApiKey(properties.dealer().key())).build();
        metricsApi = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build().createClient(MetricsApi.class);
    }

    /** DEALER's state: its sessions, queue, evictions and clients. */
    public DealerSnapshot state() {
        final var dealerState = requireNonNull(metricsApi.getDealerState().getBody(), "no state in the response");
        final var sessions = orEmpty(dealerState.getSessions()).stream()
                .map(DealerClient::sessionSnapshot)
                .toList();
        final var queue = orEmpty(dealerState.getQueue()).stream()
                .map(DealerClient::leaseRequestSnapshot)
                .toList();
        final var evictions = orEmpty(dealerState.getEvictions()).stream()
                .map(DealerClient::eviction)
                .toList();
        final var clients = orEmpty(dealerState.getClients()).stream()
                .map(client -> new ConfiguredClient(client.getName(), client.getPriority(), Boolean.TRUE.equals(client.getExternalRouting())))
                .toList();
        return new DealerSnapshot(sessions, queue, evictions, clients);
    }

    private static SessionSnapshot sessionSnapshot(final SessionInfo session) {
        return new SessionSnapshot(session.getClient(), session.getSessionId(), session.getModel(), session.getDeckServer(), session.getServerType(),
                session.getProfileId(), session.getLeaseState(), mapNullable(session.getLeaseAgeMs(), Duration::ofMillis),
                mapNullable(session.getIdleMs(), Duration::ofMillis), orZero(session.getRequests()), orZero(session.getPromptTokens()),
                orZero(session.getCachedTokens()), orZero(session.getCompletionTokens()), session.getCostUsd() == null ? 0 : session.getCostUsd());
    }

    private static LeaseRequestSnapshot leaseRequestSnapshot(final QueuedRequest queuedRequest) {
        return new LeaseRequestSnapshot(queuedRequest.getClient(), queuedRequest.getSessionId(), queuedRequest.getModel(), queuedRequest.getClientPriority(),
                queuedRequest.getRequestPriority(), mapNullable(queuedRequest.getWaitingMs(), Duration::ofMillis), queuedRequest.getReservedProfile());
    }

    private static Eviction eviction(final EvictionEvent evictionEvent) {
        return new Eviction(mapNullable(evictionEvent.getCreatedAt(), Instant::ofEpochSecond), evictionEvent.getVictimClient(),
                evictionEvent.getVictimSession(), evictionEvent.getDeckServer(), evictionEvent.getProfileId(), evictionEvent.getByClient(),
                evictionEvent.getReason());
    }

    /** A list the API may leave out, as an empty list when it does. */
    private static <T> List<T> orEmpty(final @Nullable List<T> list) {
        return list == null ? List.of() : list;
    }

    private static int orZero(final @Nullable Integer count) {
        return count == null ? 0 : count;
    }

    private static long orZero(final @Nullable Long count) {
        return count == null ? 0 : count;
    }
}
