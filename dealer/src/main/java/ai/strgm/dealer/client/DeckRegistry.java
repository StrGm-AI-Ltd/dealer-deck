package ai.strgm.dealer.client;

import static ai.strgm.util.Nulls.mapNullable;
import static java.util.Objects.requireNonNull;

import ai.strgm.dealer.config.DealerProperties;
import ai.strgm.dealer.exception.DeckErrorException;
import ai.strgm.dealer.exception.DeckUnreachableException;
import ai.strgm.dealer.exception.LeaseBlockedException;
import ai.strgm.dealer.model.DeckServerRef;
import ai.strgm.dealer.model.ModelProfileRef;
import ai.strgm.deck.client.DeckChatStream;
import ai.strgm.deck.client.DeckClient;
import ai.strgm.deck.client.config.DeckEndpoint;
import ai.strgm.deck.client.config.DeckTimeouts;
import ai.strgm.deck.client.exception.DeckConnectionException;
import ai.strgm.deck.client.exception.DeckLeaseConflictException;
import ai.strgm.deck.client.exception.DeckResponseException;
import ai.strgm.deck.client.model.DeckLease;
import ai.strgm.deck.client.model.DeckProfile;
import ai.strgm.types.LeaseId;
import ai.strgm.types.ModelName;
import ai.strgm.types.ServerName;
import ai.strgm.types.SessionRef;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The configured DECK servers, each called through its own {@link DeckClient}, which returns {@link DeckProfile}s and
 * {@link DeckLease}s. The client's exceptions stay here: the rest of DEALER sees DEALER's own.
 */
@Component
public class DeckRegistry {
    private final Map<ServerName, DeckServerRef> servers = new LinkedHashMap<>();
    private final Map<ServerName, DeckClient> deckClients = new HashMap<>();
    private final Map<ServerName, Boolean> reachable = new ConcurrentHashMap<>();
    private volatile List<ModelProfileRef> profiles = List.of();

    public DeckRegistry(final DealerProperties properties, final ObjectMapper mapper) {
        final var timeouts = new DeckTimeouts(Duration.ofSeconds(properties.timeouts().deckConnectSeconds()),
                Duration.ofSeconds(properties.timeouts().deckReadSeconds()), Duration.ofSeconds(properties.timeouts().chatConnectSeconds()),
                Duration.ofMinutes(properties.timeouts().chatRequestMinutes()));
        properties.servers().forEach(serverConfig -> {
            final var credential = mapNullable(serverConfig.providerCredentialEnv(), System::getenv);
            servers.put(serverConfig.name(), new DeckServerRef(serverConfig));
            deckClients.put(serverConfig.name(), new DeckClient(new DeckEndpoint(serverConfig.url(), serverConfig.key(),
                    credential == null || credential.isBlank() ? null : credential), timeouts, mapper));
        });
        refreshProfiles();
    }

    private DeckClient clientOf(final DeckServerRef serverRef) {
        return requireNonNull(deckClients.get(serverRef.name()), "every configured DECK server has a client");
    }

    @Scheduled(fixedDelayString = "${dealer.timers.deck-refresh-millis}")
    public void refreshProfiles() {
        final List<ModelProfileRef> next = new ArrayList<>();
        servers.values().forEach(serverRef -> {
            try {
                clientOf(serverRef).modelProfiles().forEach(profile -> next.add(new ModelProfileRef(serverRef, profile)));
                reachable.put(serverRef.name(), true);
            } catch (Exception exception) {
                reachable.put(serverRef.name(), false);
                profiles.stream()
                        .filter(profileRef -> profileRef.serverRef() == serverRef)
                        .forEach(next::add);
            }
        });
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

    public DeckLease acquireLease(final ModelProfileRef profileRef, final SessionRef sessionRef) throws LeaseBlockedException {
        try {
            return clientOf(profileRef.serverRef()).acquireLease(profileRef.profile().id(), sessionRef);
        } catch (DeckLeaseConflictException exception) {
            throw new LeaseBlockedException(exception.blockers());
        }
    }

    public DeckLease lease(final DeckServerRef serverRef, final LeaseId leaseId) {
        return clientOf(serverRef).lease(leaseId);
    }

    public void releaseLease(final DeckServerRef serverRef, final LeaseId leaseId) {
        try {
            clientOf(serverRef).releaseLease(leaseId);
        } catch (Exception ignored) {
            // the lease may already be gone
        }
    }

    /**
     * Opens a streamed chat completion of {@code modelName} on {@code serverRef}, on lease {@code leaseId}. Throws
     * {@link DeckUnreachableException} when the server can't be reached, and {@link DeckErrorException} when it refuses
     * the request.
     */
    public DeckChatStream openChatStream(final DeckServerRef serverRef, final LeaseId leaseId, final ModelName modelName, final JsonNode chatRequest) {
        try {
            return clientOf(serverRef).openChatStream(leaseId, modelName, chatRequest);
        } catch (DeckConnectionException exception) {
            throw new DeckUnreachableException("DECK server " + serverRef.name() + " unreachable");
        } catch (DeckResponseException exception) {
            throw new DeckErrorException(exception.status(), exception.body());
        }
    }

    public void evictLease(final DeckServerRef serverRef, final LeaseId leaseId) {
        try {
            clientOf(serverRef).evictLease(leaseId);
        } catch (Exception ignored) {
            // the lease may already be gone
        }
    }
}
