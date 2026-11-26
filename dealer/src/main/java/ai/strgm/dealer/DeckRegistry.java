package ai.strgm.dealer;

import ai.strgm.deck.api.client.LeasesApi;
import ai.strgm.deck.api.client.ModelProfilesApi;
import ai.strgm.deck.api.model.Lease;
import ai.strgm.deck.api.model.LeaseRequest;
import ai.strgm.deck.api.model.ModelProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    public record Server(DealerProperties.ServerDef def, ModelProfilesApi modelProfiles, LeasesApi leases,
            @Nullable String providerAuthorization) {
        public boolean external() {
            return "external".equals(def.egress());
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
            requests.setConnectTimeout(2000);
            requests.setReadTimeout(10000);
            final var rest = RestClient.builder().requestFactory(requests).baseUrl(def.url())
                    .defaultHeader("Authorization", "Bearer " + def.key()).build();
            final var factory = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest)).build();
            final var credential = def.providerCredentialEnv() == null ? null : System.getenv(def.providerCredentialEnv());
            servers.put(def.name(), new Server(def, factory.createClient(ModelProfilesApi.class),
                    factory.createClient(LeasesApi.class), credential == null || credential.isBlank() ? null : "Bearer " + credential));
        }
        refresh();
    }

    @Scheduled(fixedDelay = 1000)
    public void refresh() {
        final List<ProfileRef> next = new ArrayList<>();
        for (final var s : servers.values()) {
            try {
                final var offered = Objects.requireNonNull(s.modelProfiles().getModelProfiles().getBody(), "no model profiles in the response");
                offered.getProfiles().forEach(p -> next.add(new ProfileRef(s, p)));
                reachable.put(s.name(), true);
            } catch (Exception e) {
                reachable.put(s.name(), false);
                profiles.stream().filter(p -> p.server() == s).forEach(next::add);
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

    public Lease acquire(final ProfileRef p, final String sessionRef) throws Blocked {
        try {
            return Objects.requireNonNull(p.server().leases().acquireLease(new LeaseRequest(p.profile().getId(), sessionRef)).getBody(),
                    "no lease in the response");
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 409) {
                final var node = mapper.readTree(e.getResponseBodyAsString()).path("blockers");
                final List<String> blockers = new ArrayList<>();
                for (int i = 0; i < node.size(); i++) {
                    blockers.add(node.get(i).asString());
                }
                throw new Blocked(blockers);
            }
            throw e;
        }
    }

    public Lease lease(final Server s, final String leaseId) {
        return Objects.requireNonNull(s.leases().getLease(leaseId).getBody(), "no lease in the response");
    }

    public void release(final Server s, final String leaseId) {
        try {
            s.leases().releaseLease(leaseId);
        } catch (Exception ignored) {
            // the lease may already be gone
        }
    }

    public void evict(final Server s, final String leaseId) {
        try {
            s.leases().evictLease(leaseId);
        } catch (Exception ignored) {
            // the lease may already be gone
        }
    }
}
