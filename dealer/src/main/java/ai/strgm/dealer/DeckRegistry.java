package ai.strgm.dealer;

import ai.strgm.dealer.api.model.ServerType;
import ai.strgm.deck.api.client.LeasesApi;
import ai.strgm.deck.api.client.ModelProfilesApi;
import ai.strgm.deck.api.model.Lease;
import ai.strgm.deck.api.model.LeaseRequest;
import ai.strgm.deck.api.model.ModelProfile;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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


    public record Server(DealerProperties.ServerDef def, ModelProfilesApi modelProfiles, LeasesApi leases,
            @Nullable String providerAuthorization) {
        public boolean external() {
            return def.egress() == ServerType.EXTERNAL;
        }

        public String name() {
            return def.name();
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
        final List<String> blockers;

        Blocked(final List<String> blockers) {
            super("blocked by " + blockers);
            this.blockers = blockers;
        }
    }

    private final Map<String, Server> servers = new LinkedHashMap<>();
    private final Map<String, Boolean> reachable = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private volatile List<ProfileRef> profiles = List.of();

    public DeckRegistry(final DealerProperties props, final ObjectMapper mapper) {
        this.mapper = mapper;
        for (final var def : props.servers()) {
            final var requests = new SimpleClientHttpRequestFactory();
            requests.setConnectTimeout(CONNECT_TIMEOUT);
            requests.setReadTimeout(READ_TIMEOUT);
            final var rest = RestClient.builder().requestFactory(requests).baseUrl(def.url())
                    .defaultHeader("Authorization", "Bearer " + def.key()).build();
            final var factory = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest)).build();
            final var credential = def.providerCredentialEnv() == null ? null : System.getenv(def.providerCredentialEnv());
            servers.put(def.name(), new Server(def, factory.createClient(ModelProfilesApi.class),
                    factory.createClient(LeasesApi.class), credential == null || credential.isBlank() ? null : "Bearer " + credential));
        }
        refresh();
    }

    @Scheduled(fixedDelay = REFRESH_INTERVAL_MS)
    public void refresh() {
        final List<ProfileRef> next = new ArrayList<>();
        for (final var server : servers.values()) {
            try {
                final var offered = Objects.requireNonNull(server.modelProfiles().getModelProfiles().getBody(), "no model profiles in the response");
                offered.getProfiles().forEach(profile -> next.add(new ProfileRef(server, profile)));
                reachable.put(server.name(), true);
            } catch (Exception exception) {
                reachable.put(server.name(), false);
                profiles.stream().filter(profileRef -> profileRef.server() == server).forEach(next::add);
            }
        }
        profiles = List.copyOf(next);
    }

    public List<ProfileRef> profiles() {
        return profiles;
    }

    public Map<String, Server> servers() {
        return servers;
    }

    public boolean reachable(final String server) {
        return reachable.getOrDefault(server, false);
    }

    public Lease acquire(final ProfileRef profileRef, final String sessionRef) throws Blocked {
        try {
            return Objects.requireNonNull(profileRef.server().leases().acquireLease(new LeaseRequest(profileRef.profile().getId(), sessionRef)).getBody(),
                    "no lease in the response");
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
                final var node = mapper.readTree(exception.getResponseBodyAsString()).path("blockers");
                final List<String> blockers = new ArrayList<>();
                for (int blockerIndex = 0; blockerIndex < node.size(); blockerIndex++) {
                    blockers.add(node.get(blockerIndex).asString());
                }
                throw new Blocked(blockers);
            }
            throw exception;
        }
    }

    public Lease lease(final Server server, final String leaseId) {
        return Objects.requireNonNull(server.leases().getLease(leaseId).getBody(), "no lease in the response");
    }

    public void release(final Server server, final String leaseId) {
        try {
            server.leases().releaseLease(leaseId);
        } catch (Exception ignored) {
            // the lease may already be gone
        }
    }

    public void evict(final Server server, final String leaseId) {
        try {
            server.leases().evictLease(leaseId);
        } catch (Exception ignored) {
            // the lease may already be gone
        }
    }
}
