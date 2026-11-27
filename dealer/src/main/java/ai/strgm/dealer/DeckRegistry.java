package ai.strgm.dealer;

import static ai.strgm.util.Nulls.mapNullable;
import static java.lang.Thread.currentThread;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.OK;

import ai.strgm.dealer.api.model.ServerType;
import ai.strgm.deck.api.client.LeasesApi;
import ai.strgm.deck.api.client.ModelProfilesApi;
import ai.strgm.dealer.DeckProfile.TokenPrices;
import ai.strgm.deck.api.model.Lease;
import ai.strgm.deck.api.model.LeaseConflict;
import ai.strgm.deck.api.model.LeaseReduction;
import ai.strgm.deck.api.model.LeaseRequest;
import ai.strgm.deck.api.model.ModelProfile;
import ai.strgm.types.LeaseId;
import ai.strgm.types.ServerName;
import ai.strgm.types.SessionRef;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * The configured DECK servers, called only through the clients generated from the DECK OpenAPI document. DECK's models
 * stay here: the rest of DEALER sees them as {@link DeckProfile}s and {@link DeckLease}s.
 */
@Component
public class DeckRegistry {


    public record DeckServerRef(DealerProperties.DeckServerConfig config, ModelProfilesApi modelProfiles, LeasesApi leases,
            @Nullable String providerAuthorization) {
        public boolean external() {
            return config.egress() == ServerType.EXTERNAL;
        }

        public ServerName name() {
            return config.name();
        }
    }

    public record ModelProfileRef(DeckServerRef serverRef, DeckProfile profile) {
        public String key() {
            return serverRef.name() + "/" + profile.id();
        }

        public boolean external() {
            return serverRef.external();
        }
    }

    /** DECK refused a lease; {@code blockers} are the leases in the way. */
    public static class Blocked extends Exception {
        final List<LeaseId> blockers;

        Blocked(final List<LeaseId> blockers) {
            super("blocked by " + blockers);
            this.blockers = blockers;
        }
    }

    private final Map<ServerName, DeckServerRef> servers = new LinkedHashMap<>();
    private final Map<ServerName, Boolean> reachable = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final HttpClient chatClient;
    /** How long one chat completion may take, streaming included. */
    private final Duration chatRequestTimeout;
    private volatile List<ModelProfileRef> profiles = List.of();

    public DeckRegistry(final DealerProperties properties, final ObjectMapper mapper) {
        this.mapper = mapper;
        this.chatClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(properties.timeouts().chatConnectSeconds())).build();
        this.chatRequestTimeout = Duration.ofMinutes(properties.timeouts().chatRequestMinutes());
        for (final var serverConfig : properties.servers()) {
            final var requests = new SimpleClientHttpRequestFactory();
            requests.setConnectTimeout(Duration.ofSeconds(properties.timeouts().deckConnectSeconds()));
            requests.setReadTimeout(Duration.ofSeconds(properties.timeouts().deckReadSeconds()));
            final var restClient = RestClient.builder().requestFactory(requests).baseUrl(serverConfig.url())
                    .defaultHeader("Authorization", "Bearer " + serverConfig.key()).build();
            final var factory = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build();
            final var credential = mapNullable(serverConfig.providerCredentialEnv(), System::getenv);
            servers.put(serverConfig.name(), new DeckServerRef(serverConfig, factory.createClient(ModelProfilesApi.class),
                    factory.createClient(LeasesApi.class), credential == null || credential.isBlank() ? null : "Bearer " + credential));
        }
        refresh();
    }

    @Scheduled(fixedDelayString = "${dealer.timers.deck-refresh-millis}")
    public void refresh() {
        final List<ModelProfileRef> next = new ArrayList<>();
        for (final var serverRef : servers.values()) {
            try {
                final var offeredProfiles = requireNonNull(serverRef.modelProfiles().getModelProfiles().getBody(),
                        "no model profiles in the response");
                offeredProfiles.getProfiles().forEach(profile -> next.add(new ModelProfileRef(serverRef, deckProfile(profile))));
                reachable.put(serverRef.name(), true);
            } catch (Exception exception) {
                reachable.put(serverRef.name(), false);
                profiles.stream()
                        .filter(profileRef -> profileRef.serverRef() == serverRef)
                        .forEach(next::add);
            }
        }
        profiles = List.copyOf(next);
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
        return new DeckLease(lease.getId(), lease.getState(), lease.getReadyAtMs());
    }

    public List<ModelProfileRef> profiles() {
        return profiles;
    }

    public Map<ServerName, DeckServerRef> servers() {
        return servers;
    }

    public boolean reachable(final ServerName serverName) {
        return reachable.getOrDefault(serverName, false);
    }

    public DeckLease acquire(final ModelProfileRef profileRef, final SessionRef sessionRef) throws Blocked {
        try {
            return deckLease(requireNonNull(profileRef.serverRef().leases().acquireLease(new LeaseRequest(profileRef.profile().id(), sessionRef)).getBody(),
                    "no lease in the response"));
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode().isSameCodeAs(CONFLICT)) {
                throw new Blocked(mapper.readValue(exception.getResponseBodyAsString(), LeaseConflict.class).getBlockers());
            }
            throw exception;
        }
    }

    public DeckLease lease(final DeckServerRef serverRef, final LeaseId leaseId) {
        return deckLease(requireNonNull(serverRef.leases().getLease(leaseId).getBody(), "no lease in the response"));
    }

    public void release(final DeckServerRef serverRef, final LeaseId leaseId) {
        try {
            serverRef.leases().releaseLease(leaseId);
        } catch (Exception ignored) {
            // the lease may already be gone
        }
    }

    /**
     * Opens a streamed chat completion on {@code serverRef}, on lease {@code leaseId}, and returns its server-sent
     * events. Throws {@link DeckUnreachableException} when the server can't be reached, and {@link DeckErrorException}
     * when it refuses the request.
     */
    public InputStream openChat(final DeckServerRef serverRef, final LeaseId leaseId, final String requestBody) {
        final var request = HttpRequest.newBuilder(URI.create(serverRef.config().url() + "/v1/chat/completions"))
                .header("Authorization", "Bearer " + serverRef.config().key())
                .header("X-Lease-Id", leaseId.value())
                .header("Content-Type", "application/json")
                .timeout(chatRequestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
        if (serverRef.providerAuthorization() != null) {
            request.header("X-Provider-Authorization", serverRef.providerAuthorization());
        }
        final HttpResponse<InputStream> response;
        try {
            response = chatClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                currentThread().interrupt();
            }
            throw new DeckUnreachableException("DECK server " + serverRef.name() + " unreachable");
        }
        if (response.statusCode() != OK.value()) {
            String text;
            try (var responseBody = response.body()) {
                text = new String(responseBody.readAllBytes(), UTF_8);
            } catch (Exception exception) {
                text = "";
            }
            throw new DeckErrorException(response.statusCode(), text);
        }
        return response.body();
    }

    public void evict(final DeckServerRef serverRef, final LeaseId leaseId) {
        try {
            serverRef.leases().evictLease(leaseId);
        } catch (Exception ignored) {
            // the lease may already be gone
        }
    }
}
