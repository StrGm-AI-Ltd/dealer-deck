package ai.strgm.dealer;

import static java.util.Objects.requireNonNull;
import static org.springframework.http.HttpStatus.CONFLICT;

import ai.strgm.dealer.api.model.ServerType;
import ai.strgm.deck.api.client.LeasesApi;
import ai.strgm.deck.api.client.ModelProfilesApi;
import ai.strgm.deck.api.model.Lease;
import ai.strgm.deck.api.model.LeaseConflict;
import ai.strgm.deck.api.model.LeaseRequest;
import ai.strgm.deck.api.model.ModelProfile;
import ai.strgm.types.LeaseId;
import ai.strgm.types.ServerName;
import ai.strgm.types.SessionRef;
import ai.strgm.util.Nulls;
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

/** The configured DECK servers, called only through the clients generated from the DECK OpenAPI document. */
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

    public record ModelProfileRef(DeckServerRef serverRef, ModelProfile profile) {
        public String key() {
            return serverRef.name() + "/" + profile.getId();
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
    private volatile List<ModelProfileRef> profiles = List.of();

    public DeckRegistry(final DealerProperties properties, final ObjectMapper mapper) {
        this.mapper = mapper;
        for (final var serverConfig : properties.servers()) {
            final var requests = new SimpleClientHttpRequestFactory();
            requests.setConnectTimeout(Duration.ofSeconds(properties.timeouts().deckConnectSeconds()));
            requests.setReadTimeout(Duration.ofSeconds(properties.timeouts().deckReadSeconds()));
            final var restClient = RestClient.builder().requestFactory(requests).baseUrl(serverConfig.url())
                    .defaultHeader("Authorization", "Bearer " + serverConfig.key()).build();
            final var factory = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build();
            final var credential = Nulls.map(serverConfig.providerCredentialEnv(), System::getenv);
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
                final var offered = requireNonNull(serverRef.modelProfiles().getModelProfiles().getBody(), "no model profiles in the response");
                offered.getProfiles().forEach(profile -> next.add(new ModelProfileRef(serverRef, profile)));
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

    public List<ModelProfileRef> profiles() {
        return profiles;
    }

    public Map<ServerName, DeckServerRef> servers() {
        return servers;
    }

    public boolean reachable(final ServerName serverName) {
        return reachable.getOrDefault(serverName, false);
    }

    public Lease acquire(final ModelProfileRef profileRef, final SessionRef sessionRef) throws Blocked {
        try {
            return requireNonNull(profileRef.serverRef().leases().acquireLease(new LeaseRequest(profileRef.profile().getId(), sessionRef)).getBody(),
                    "no lease in the response");
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode().isSameCodeAs(CONFLICT)) {
                throw new Blocked(mapper.readValue(exception.getResponseBodyAsString(), LeaseConflict.class).getBlockers());
            }
            throw exception;
        }
    }

    public Lease lease(final DeckServerRef serverRef, final LeaseId leaseId) {
        return requireNonNull(serverRef.leases().getLease(leaseId).getBody(), "no lease in the response");
    }

    public void release(final DeckServerRef serverRef, final LeaseId leaseId) {
        try {
            serverRef.leases().releaseLease(leaseId);
        } catch (Exception ignored) {
            // the lease may already be gone
        }
    }

    public void evict(final DeckServerRef serverRef, final LeaseId leaseId) {
        try {
            serverRef.leases().evictLease(leaseId);
        } catch (Exception ignored) {
            // the lease may already be gone
        }
    }
}
