package ai.strgm.deck.client;

import static ai.strgm.util.Nulls.mapNullable;
import static java.util.Objects.requireNonNull;
import static org.springframework.http.HttpStatus.CONFLICT;

import ai.strgm.deck.api.client.LeasesApi;
import ai.strgm.deck.api.client.MetricsApi;
import ai.strgm.deck.api.client.ModelProfilesApi;
import ai.strgm.deck.api.model.DeckState;
import ai.strgm.deck.api.model.Lease;
import ai.strgm.deck.api.model.LeaseConflict;
import ai.strgm.deck.api.model.LeaseReduction;
import ai.strgm.deck.api.model.LeaseRequest;
import ai.strgm.deck.api.model.ModelProfile;
import ai.strgm.deck.client.config.DeckEndpoint;
import ai.strgm.deck.client.config.DeckTimeouts;
import ai.strgm.deck.client.exception.DeckConnectionException;
import ai.strgm.deck.client.exception.DeckLeaseConflictException;
import ai.strgm.deck.client.exception.DeckResponseException;
import ai.strgm.deck.client.model.DeckLease;
import ai.strgm.deck.client.model.DeckProfile;
import ai.strgm.deck.client.model.DeckProfile.TokenPrices;
import ai.strgm.types.LeaseId;
import ai.strgm.types.ModelName;
import ai.strgm.types.ProfileId;
import ai.strgm.types.SessionRef;
import ai.strgm.util.AuthorizationHeaders;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A client of one DECK server, with the DECK API's features as Java methods that take and return domain types, never
 * the models generated from the DECK OpenAPI document. It hides the HTTP underneath:
 * <ul>
 * <li>the DECK key, and the provider credential it forwards with each chat completion;</li>
 * <li>the request and response bodies, built on the HTTP interfaces generated from the DECK OpenAPI document, and the
 * mapping between the generated models and the domain types;</li>
 * <li>error statuses, which become {@link DeckResponseException}s, and connection failures, which become
 * {@link DeckConnectionException}s;</li>
 * <li>a chat completion's server-sent events, which a {@link DeckChatStream} reads.</li>
 * </ul>
 */
public final class DeckClient {
    private final String baseUrl;
    private final ModelProfilesApi modelProfilesApi;
    private final LeasesApi leasesApi;
    private final MetricsApi metricsApi;
    private final ChatStreamApi chatStreamApi;
    private final @Nullable String providerAuthorization;
    private final ObjectMapper mapper;

    public DeckClient(final DeckEndpoint endpoint, final DeckTimeouts timeouts, final ObjectMapper mapper) {
        this.baseUrl = endpoint.baseUrl();
        final var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(timeouts.connect());
        requests.setReadTimeout(timeouts.read());
        final var apis = proxyFactory(endpoint, requests);
        this.modelProfilesApi = apis.createClient(ModelProfilesApi.class);
        this.leasesApi = apis.createClient(LeasesApi.class);
        this.metricsApi = apis.createClient(MetricsApi.class);
        // a chat completion has timeouts of its own: its response may start only once a model has loaded
        final var chatRequests = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeouts.chatConnect()).build());
        chatRequests.setReadTimeout(timeouts.chatResponse());
        this.chatStreamApi = proxyFactory(endpoint, chatRequests).createClient(ChatStreamApi.class);
        this.providerAuthorization = mapNullable(endpoint.providerCredential(), AuthorizationHeaders::forProviderCredential);
        this.mapper = mapper;
    }

    private static HttpServiceProxyFactory proxyFactory(final DeckEndpoint endpoint, final ClientHttpRequestFactory requestFactory) {
        final var restClient = RestClient.builder().requestFactory(requestFactory).baseUrl(endpoint.baseUrl())
                .defaultHeader("Authorization", AuthorizationHeaders.forApiKey(endpoint.apiKey())).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build();
    }

    /** The model profiles the server offers. */
    public List<DeckProfile> modelProfiles() {
        final var offeredProfiles = call(() -> requireNonNull(modelProfilesApi.getModelProfiles().getBody(), "no model profiles in the response"));
        return offeredProfiles.getProfiles().stream()
                .map(DeckClient::deckProfile)
                .toList();
    }

    private static DeckProfile deckProfile(final ModelProfile profile) {
        final var prices = profile.getPrices();
        final var limitations = profile.getLimitations();
        final var leaseReductions = limitations == null || limitations.getLeaseReductions() == null ? List.<LeaseReduction>of()
                : limitations.getLeaseReductions();
        return new DeckProfile(profile.getId(), profile.getModel(), profile.getContextWindow(), prices == null ? TokenPrices.NONE
                : new TokenPrices(orZero(prices.getInputPerMTok()), orZero(prices.getCachedInputPerMTok()), orZero(prices.getOutputPerMTok())),
                leaseReductions.stream()
                        .map(LeaseReduction::getProfileId)
                        .toList());
    }

    private static double orZero(final @Nullable Double price) {
        return price == null ? 0 : price;
    }

    private static DeckLease deckLease(final Lease lease) {
        return new DeckLease(lease.getId(), lease.getState(), mapNullable(lease.getReadyAtMs(), Instant::ofEpochMilli));
    }

    /** Acquires a lease on {@code profileId} for {@code sessionRef}. Throws {@link DeckLeaseConflictException} when active leases are in the way. */
    public DeckLease acquireLease(final ProfileId profileId, final SessionRef sessionRef) {
        try {
            return deckLease(call(() -> requireNonNull(leasesApi.acquireLease(new LeaseRequest(profileId, sessionRef)).getBody(), "no lease in the response")));
        } catch (DeckResponseException exception) {
            if (exception.status() == CONFLICT.value()) {
                throw new DeckLeaseConflictException(exception.status(), exception.body(),
                        mapper.readValue(exception.body(), LeaseConflict.class).getBlockers());
            }
            throw exception;
        }
    }

    public DeckLease lease(final LeaseId leaseId) {
        return deckLease(call(() -> requireNonNull(leasesApi.getLease(leaseId).getBody(), "no lease in the response")));
    }

    public void releaseLease(final LeaseId leaseId) {
        call(() -> leasesApi.releaseLease(leaseId));
    }

    public void evictLease(final LeaseId leaseId) {
        call(() -> leasesApi.evictLease(leaseId));
    }

    /** The server's state: its profiles, workers and recent leases. */
    public DeckState state() {
        return call(() -> requireNonNull(metricsApi.getDeckState().getBody(), "no state in the response"));
    }

    /**
     * Opens a streamed chat completion of {@code modelName} on lease {@code leaseId}. It asks DECK to report the tokens
     * used, which the events carry. Close the stream once it's read.
     */
    public DeckChatStream openChatStream(final LeaseId leaseId, final ModelName modelName, final JsonNode chatRequest) {
        final var deckRequest = (ObjectNode) chatRequest.deepCopy();
        deckRequest.put("model", modelName.value());
        deckRequest.putObject("stream_options").put("include_usage", true);
        final var events = call(() -> chatStreamApi.openChatStream(leaseId, providerAuthorization, deckRequest.toString()));
        return new DeckChatStream(events, mapper);
    }

    /** Makes a request, and turns an error status or a connection failure into this client's exceptions. */
    private <T> T call(final Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException exception) {
            throw new DeckResponseException(exception.getStatusCode().value(), exception.getResponseBodyAsString());
        } catch (ResourceAccessException exception) {
            throw new DeckConnectionException(baseUrl, exception);
        }
    }
}
