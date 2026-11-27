package ai.strgm.dealer;

import static java.lang.System.getenv;
import static java.util.Objects.requireNonNull;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
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
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    /** How often DEALER refreshes every DECK server's model profiles. */
    private static final long REFRESH_INTERVAL_MS = 1000;


    public record Server(DealerProperties.ServerDef config, ModelProfilesApi modelProfiles, LeasesApi leases,
            @Nullable String providerAuthorization) {
        public boolean external() {
            return config.egress() == ServerType.EXTERNAL;
        }

        public ServerName name() {
            return config.name();
        }
    }

    public record ProfileRef(Server server, ModelProfile profile) {
        public String key() {
            return server.name() + "/" + profile.getId();
        }

        public boolean external() {
            return server.external();
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

    private final Map<ServerName, Server> servers = new LinkedHashMap<>();
    private final Map<ServerName, Boolean> reachable = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private volatile List<ProfileRef> profiles = List.of();

    public DeckRegistry(final DealerProperties properties, final ObjectMapper mapper) {
        this.mapper = mapper;
        for (final var serverConfig : properties.servers()) {
            final var requests = new SimpleClientHttpRequestFactory();
            requests.setConnectTimeout(CONNECT_TIMEOUT);
            requests.setReadTimeout(READ_TIMEOUT);
            final var restClient = RestClient.builder().requestFactory(requests).baseUrl(serverConfig.url())
                    .defaultHeader("Authorization", "Bearer " + serverConfig.key()).build();
            final var factory = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build();
            final var credential = serverConfig.providerCredentialEnv() == null ? null : getenv(serverConfig.providerCredentialEnv());
            servers.put(serverConfig.name(), new Server(serverConfig, factory.createClient(ModelProfilesApi.class),
                    factory.createClient(LeasesApi.class), credential == null || credential.isBlank() ? null : "Bearer " + credential));
        }
        refresh();
    }

    @Scheduled(fixedDelay = REFRESH_INTERVAL_MS)
    public void refresh() {
        final List<ProfileRef> next = new ArrayList<>();
        for (final var server : servers.values()) {
            try {
                final var offered = requireNonNull(server.modelProfiles().getModelProfiles().getBody(), "no model profiles in the response");
                offered.getProfiles().forEach(profile -> next.add(new ProfileRef(server, profile)));
                reachable.put(server.name(), true);
            } catch (Exception exception) {
                reachable.put(server.name(), false);
                profiles.stream()
                        .filter(profileRef -> profileRef.server() == server)
                        .forEach(next::add);
            }
        }
        profiles = List.copyOf(next);
    }

    public List<ProfileRef> profiles() {
        return profiles;
    }

    public Map<ServerName, Server> servers() {
        return servers;
    }

    public boolean reachable(final ServerName server) {
        return reachable.getOrDefault(server, false);
    }

    public Lease acquire(final ProfileRef profileRef, final SessionRef sessionRef) throws Blocked {
        try {
            return requireNonNull(profileRef.server().leases().acquireLease(new LeaseRequest(profileRef.profile().getId(), sessionRef)).getBody(),
                    "no lease in the response");
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
                throw new Blocked(mapper.readValue(exception.getResponseBodyAsString(), LeaseConflict.class).getBlockers());
            }
            throw exception;
        }
    }

    public Lease lease(final Server server, final LeaseId leaseId) {
        return requireNonNull(server.leases().getLease(leaseId).getBody(), "no lease in the response");
    }

    public void release(final Server server, final LeaseId leaseId) {
        try {
            server.leases().releaseLease(leaseId);
        } catch (Exception ignored) {
            // the lease may already be gone
        }
    }

    public void evict(final Server server, final LeaseId leaseId) {
        try {
            server.leases().evictLease(leaseId);
        } catch (Exception ignored) {
            // the lease may already be gone
        }
    }
}
